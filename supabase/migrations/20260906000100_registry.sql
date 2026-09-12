-- SPDX-License-Identifier: AGPL-3.0-only
-- Greenfield Supabase/PostgreSQL 15 migration. DO NOT combine with the old illustrative schema.
-- Auth roles supplied by Supabase: anon/authenticated/service_role. Self-hosts must provision equivalents.
BEGIN;
CREATE SCHEMA IF NOT EXISTS extensions;
CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA extensions;
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA extensions;
CREATE SCHEMA app_private;
REVOKE ALL ON SCHEMA app_private FROM PUBLIC, anon, authenticated, service_role;
DO $$ BEGIN
  IF NOT EXISTS(SELECT 1 FROM pg_catalog.pg_roles WHERE rolname='openfuel_publisher') THEN
    CREATE ROLE openfuel_publisher NOLOGIN;
  END IF;
END $$;
CREATE TYPE app_private.station_status AS ENUM('proposed','verified','temporarily_closed','permanently_closed','merged');
CREATE TABLE app_private.stations (
  id uuid PRIMARY KEY,
  name text NOT NULL CHECK(length(name) BETWEEN 1 AND 150),
  brand text NOT NULL DEFAULT '', address text NOT NULL DEFAULT '',
  region text NOT NULL CHECK(region ~ '^[a-z0-9-]{1,64}$'),
  location extensions.geography(Point,4326) NOT NULL,
  status app_private.station_status NOT NULL,
  source_license text NOT NULL CHECK(length(source_license) BETWEEN 1 AND 100),
  source_ref text NOT NULL CHECK(length(source_ref) BETWEEN 1 AND 200),
  is_demo boolean NOT NULL DEFAULT false,
  version bigint NOT NULL CHECK(version>0),
  merged_into uuid REFERENCES app_private.stations(id),
  CHECK ((status='merged')=(merged_into IS NOT NULL)),
  CHECK (merged_into IS NULL OR merged_into<>id)
);
CREATE INDEX stations_geog_gist ON app_private.stations USING gist(location);
CREATE INDEX stations_region ON app_private.stations(region,status,id);
CREATE TABLE app_private.ledger_head (
  singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
  sequence bigint NOT NULL DEFAULT 0, hash text NOT NULL DEFAULT repeat('0',64)
);
INSERT INTO app_private.ledger_head(singleton) VALUES(true);
CREATE TABLE app_private.events (
  sequence bigint PRIMARY KEY, station_id uuid NOT NULL REFERENCES app_private.stations(id),
  kind text NOT NULL, public_window timestamptz NOT NULL,
  payload_utf8 text NOT NULL,
  previous_hash text NOT NULL CHECK(previous_hash ~ '^[a-f0-9]{64}$'),
  hash text NOT NULL UNIQUE CHECK(hash ~ '^[a-f0-9]{64}$')
);
CREATE TABLE app_private.price_observations (
  event_seq bigint PRIMARY KEY REFERENCES app_private.events(sequence),
  station_id uuid NOT NULL REFERENCES app_private.stations(id),
  grade text NOT NULL CHECK(grade IN ('regular','midgrade','premium','diesel')),
  payment text NOT NULL CHECK(payment IN ('standard','cash','credit','membership')),
  -- Pricing for a named membership program is not enabled until eligibility is modeled.
  milli_cad_per_litre integer NOT NULL CHECK(milli_cad_per_litre BETWEEN 50 AND 15000),
  observed_window timestamptz NOT NULL,
  source text NOT NULL CHECK(source IN ('community','operator','licensed_feed','synthetic_fixture')),
  retracted_at_event bigint REFERENCES app_private.events(sequence),
  CHECK(retracted_at_event IS NULL OR retracted_at_event>event_seq)
);
CREATE INDEX price_latest ON app_private.price_observations(station_id,grade,payment,observed_window DESC,event_seq DESC) WHERE retracted_at_event IS NULL;
-- Private staged proposals. No public read/write grants and no user-facing write API in this delivery.
CREATE TABLE app_private.proposals (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), station_id uuid REFERENCES app_private.stations(id),
 change_kind text NOT NULL CHECK(change_kind IN ('addition','correction','temporary_closure','permanent_closure','reopening','merge')),
 expected_version bigint, proposed_fields jsonb NOT NULL,
 state text NOT NULL DEFAULT 'received' CHECK(state IN ('received','needs_evidence','disputed','approved','rejected')),
 received_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL DEFAULT now()+interval '30 days'
);
CREATE TABLE app_private.evidence (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), proposal_id uuid NOT NULL REFERENCES app_private.proposals(id) ON DELETE CASCADE,
 independence_group text NOT NULL, private_object_key text, reviewed boolean NOT NULL DEFAULT false,
 expires_at timestamptz NOT NULL DEFAULT now()+interval '30 days', UNIQUE(proposal_id,independence_group)
);
CREATE FUNCTION app_private.reject_ledger_mutation() RETURNS trigger LANGUAGE plpgsql SET search_path='' AS $$
BEGIN RAISE EXCEPTION 'Accepted history is append-only; append a correction' USING ERRCODE='55000'; END $$;
CREATE TRIGGER immutable_event BEFORE UPDATE OR DELETE ON app_private.events FOR EACH ROW EXECUTE FUNCTION app_private.reject_ledger_mutation();
CREATE TRIGGER no_event_truncate BEFORE TRUNCATE ON app_private.events FOR EACH STATEMENT EXECUTE FUNCTION app_private.reject_ledger_mutation();
CREATE FUNCTION app_private.window_15m(t timestamptz) RETURNS timestamptz LANGUAGE sql IMMUTABLE STRICT SET search_path='' AS $$
 SELECT pg_catalog.to_timestamp(floor(extract(epoch FROM t)/900)*900);
$$;
CREATE FUNCTION app_private.append_event(s uuid,k text,p jsonb) RETURNS bigint LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE h app_private.ledger_head%ROWTYPE; n bigint; payload text; digest text; w timestamptz;
BEGIN
 SELECT * INTO h FROM app_private.ledger_head WHERE singleton FOR UPDATE;
 n:=h.sequence+1; w:=app_private.window_15m(clock_timestamp());
 payload:=jsonb_build_object('schema','openfuel-registry-v1','station_id',s,'kind',k,'public_window',w,'data',p)::text;
 -- Explicit protocol domain: not silently compatible with the original SQLite event bytes.
 digest:=encode(extensions.digest(convert_to('openfuel-registry-v1','UTF8') || decode('00','hex') || decode(h.hash,'hex') || int8send(n) || convert_to(payload,'UTF8'),'sha256'),'hex');
 INSERT INTO app_private.events VALUES(n,s,k,w,payload,h.hash,digest);
 UPDATE app_private.ledger_head SET sequence=n,hash=digest WHERE singleton;
 RETURN n;
END $$;
-- Trusted publication primitive, NOT anonymous contribution or proof of review.
-- The caller must be a separately authorized publisher service after human/evidence review.
-- It has no HTTP grants; do not give its role to a phone, browser or this read-only Worker.
CREATE FUNCTION app_private.publish_station(
 p_id uuid,p_expected_version bigint,p_name text,p_brand text,p_address text,p_region text,
 p_latitude double precision,p_longitude double precision,p_status app_private.station_status,
 p_source_license text,p_source_ref text,p_is_demo boolean,p_reason text,p_merged_into uuid DEFAULT NULL
) RETURNS bigint LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actual bigint; new_version bigint; event_seq bigint;
BEGIN
 IF p_id IS NULL OR p_expected_version IS NULL OR p_expected_version<0 OR p_reason IS NULL OR p_reason NOT IN ('initial_verification','detail_correction','temporary_closure','permanent_closure','verified_reopening','duplicate_merge') THEN
   RAISE EXCEPTION 'Invalid publication arguments' USING ERRCODE='22023';
 END IF;
 IF p_latitude IS NULL OR p_longitude IS NULL OR p_latitude NOT BETWEEN -90 AND 90 OR p_longitude NOT BETWEEN -180 AND 180 THEN
   RAISE EXCEPTION 'Invalid station coordinates' USING ERRCODE='22023';
 END IF;
 -- Serialize the short publish transaction, including competing new-station creates.
 PERFORM singleton FROM app_private.ledger_head WHERE singleton FOR UPDATE;
 SELECT version INTO actual FROM app_private.stations WHERE id=p_id FOR UPDATE;
 IF COALESCE(actual,0)<>p_expected_version THEN RAISE EXCEPTION 'Station version conflict' USING ERRCODE='40001'; END IF;
 new_version:=p_expected_version+1;
 INSERT INTO app_private.stations(id,name,brand,address,region,location,status,source_license,source_ref,is_demo,version,merged_into)
 VALUES(p_id,p_name,p_brand,p_address,p_region,extensions.st_setsrid(extensions.st_makepoint(p_longitude,p_latitude),4326)::extensions.geography,p_status,p_source_license,p_source_ref,p_is_demo,new_version,p_merged_into)
 ON CONFLICT(id) DO UPDATE SET name=excluded.name,brand=excluded.brand,address=excluded.address,region=excluded.region,location=excluded.location,status=excluded.status,source_license=excluded.source_license,source_ref=excluded.source_ref,is_demo=excluded.is_demo,version=excluded.version,merged_into=excluded.merged_into;
 event_seq:=app_private.append_event(p_id,'station.changed',jsonb_build_object('version',new_version,'name',p_name,'brand',p_brand,'address',p_address,'region',p_region,'latitude',p_latitude,'longitude',p_longitude,'status',p_status,'source_license',p_source_license,'source_ref',p_source_ref,'is_demo',p_is_demo,'reason',p_reason,'merged_into',p_merged_into));
 RETURN event_seq;
END $$;
CREATE FUNCTION app_private.publish_price(p_id uuid,p_grade text,p_payment text,p_price integer,p_observed timestamptz,p_source text) RETURNS bigint LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE n bigint; w timestamptz;
BEGIN
 PERFORM singleton FROM app_private.ledger_head WHERE singleton FOR UPDATE;
 IF p_payment='membership' THEN RAISE EXCEPTION 'Membership eligibility not implemented' USING ERRCODE='22023'; END IF;
 IF p_observed IS NULL OR p_observed>now()+interval '5 minutes' OR p_observed<now()-interval '48 hours' THEN RAISE EXCEPTION 'Observation time outside range' USING ERRCODE='22023'; END IF;
 IF NOT EXISTS(SELECT 1 FROM app_private.stations WHERE id=p_id AND status='verified') THEN RAISE EXCEPTION 'Only verified stations can receive prices' USING ERRCODE='22023'; END IF;
 w:=app_private.window_15m(p_observed);
 n:=app_private.append_event(p_id,'price.observed',jsonb_build_object('grade',p_grade,'payment',p_payment,'price_milli',p_price,'observed_window',w,'source',p_source));
 INSERT INTO app_private.price_observations(event_seq,station_id,grade,payment,milli_cad_per_litre,observed_window,source) VALUES(n,p_id,p_grade,p_payment,p_price,w,p_source);
 RETURN n;
END $$;
CREATE FUNCTION app_private.retract_price(p_event bigint,p_reason text) RETURNS bigint LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE station uuid; n bigint;
BEGIN
 IF p_reason IS NULL OR p_reason NOT IN ('incorrect_price','incorrect_conditions','moderation') THEN RAISE EXCEPTION 'Invalid reason' USING ERRCODE='22023'; END IF;
 PERFORM singleton FROM app_private.ledger_head WHERE singleton FOR UPDATE;
 SELECT station_id INTO station FROM app_private.price_observations WHERE event_seq=p_event AND retracted_at_event IS NULL FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'No active observation' USING ERRCODE='22023'; END IF;
 n:=app_private.append_event(station,'price.retracted',jsonb_build_object('target_event',p_event,'reason',p_reason));
 UPDATE app_private.price_observations SET retracted_at_event=n WHERE event_seq=p_event;RETURN n;
END $$;
ALTER TABLE app_private.stations ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_private.events ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_private.price_observations ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_private.ledger_head ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_private.proposals ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_private.evidence ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON ALL TABLES IN SCHEMA app_private FROM PUBLIC,anon,authenticated,service_role;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA app_private FROM PUBLIC,anon,authenticated,service_role;
GRANT USAGE ON SCHEMA app_private TO openfuel_publisher;
GRANT EXECUTE ON FUNCTION app_private.publish_station(uuid,bigint,text,text,text,text,double precision,double precision,app_private.station_status,text,text,boolean,text,uuid),app_private.publish_price(uuid,text,text,integer,timestamptz,text),app_private.retract_price(bigint,text) TO openfuel_publisher;
ALTER DEFAULT PRIVILEGES IN SCHEMA app_private REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
COMMIT;
