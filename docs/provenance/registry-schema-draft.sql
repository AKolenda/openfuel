-- HISTORICAL PROPOSAL ONLY. Canonical greenfield migrations now live under supabase/migrations.
-- SPDX-License-Identifier: AGPL-3.0-only
-- PostgreSQL / PostGIS DESIGN DRAFT. Not executed against PostgreSQL in this turn.
-- No client writes, auth bootstrap, moderators, accounts or private API permissions are provisioned.
-- In Supabase, configure the API exposed schemas and roles deliberately; do not expose of_private.
BEGIN;
CREATE SCHEMA IF NOT EXISTS extensions;
CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA extensions;
SET LOCAL search_path = public, extensions;
CREATE SCHEMA IF NOT EXISTS of_public;
CREATE SCHEMA IF NOT EXISTS of_private;
REVOKE ALL ON SCHEMA of_private FROM PUBLIC;
CREATE TABLE of_public.stations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name text NOT NULL CHECK (length(name) BETWEEN 2 AND 160),
    brand text,
    address text NOT NULL,
    province_code text NOT NULL CHECK (province_code IN ('AB','BC','MB','NB','NL','NS','NT','NU','ON','PE','QC','SK','YT')),
    location geography(Point,4326) NOT NULL,
    status text NOT NULL CHECK (status IN ('active','temporarily_closed','permanently_closed','duplicate')),
    version integer NOT NULL CHECK (version >= 1),
    last_verified_date date,
    duplicate_of uuid REFERENCES of_public.stations(id),
    -- Conservative intended license for a future OSM-derived canonical station dataset.
    -- Schema alone cannot decide the legal classification of a combined database.
    license_ref text NOT NULL DEFAULT 'ODbL-1.0',
    CHECK ((status = 'duplicate') = (duplicate_of IS NOT NULL)),
    CHECK (duplicate_of IS NULL OR duplicate_of <> id)
);
CREATE INDEX stations_location_gist ON of_public.stations USING gist(location);
CREATE INDEX stations_province_status ON of_public.stations(province_code,status);
CREATE TABLE of_public.station_sources (
    station_id uuid NOT NULL REFERENCES of_public.stations(id),
    source_type text NOT NULL CHECK (source_type IN ('osm','community','operator','open_government')),
    external_id text NOT NULL,
    source_version text,
    license_ref text NOT NULL,
    last_seen_date date NOT NULL,
    PRIMARY KEY (source_type,external_id),
    CHECK (source_type <> 'osm' OR license_ref = 'ODbL-1.0')
);
CREATE TABLE of_private.station_proposals (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    station_id uuid REFERENCES of_public.stations(id),
    base_version integer,
    change_type text NOT NULL CHECK (change_type IN ('new','edit','temporary_close','permanent_close','reopen','merge')),
    proposed_fields jsonb NOT NULL,
    state text NOT NULL DEFAULT 'pending_review' CHECK (state IN ('pending_review','accepted','rejected','needs_more_evidence','stale')),
    created_at timestamptz NOT NULL DEFAULT now(),
    -- Raw evidence stays private, outside the permanent public ledger; TTL requires a worker.
    evidence_expires_at timestamptz NOT NULL DEFAULT now() + interval '30 days',
    CHECK ((change_type='new') = (station_id IS NULL))
);
CREATE TABLE of_private.attestations (
    proposal_id uuid NOT NULL REFERENCES of_private.station_proposals(id),
    proposal_scoped_token bytea NOT NULL CHECK (octet_length(proposal_scoped_token)=32),
    evidence_reference text,
    claimed_kind text NOT NULL CHECK (claimed_kind IN ('visit','operator','public_source')),
    received_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (proposal_id,proposal_scoped_token)
);
CREATE TABLE of_private.moderation_decisions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    proposal_id uuid NOT NULL REFERENCES of_private.station_proposals(id),
    reviewer_id uuid NOT NULL,
    decision text NOT NULL CHECK (decision IN ('approve','reject','request_evidence')),
    decision_reason text NOT NULL,
    decided_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (proposal_id,reviewer_id)
);
CREATE TABLE of_public.station_events (
    sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    station_id uuid NOT NULL REFERENCES of_public.stations(id),
    version integer NOT NULL,
    event_type text NOT NULL CHECK (event_type IN ('station_added','details_corrected','temporary_closure_verified','permanent_closure_verified','reopened','merged')),
    published_date date NOT NULL,
    -- Strictly allowlisted station facts, moderated before publication. No public reporter IDs.
    station_snapshot jsonb NOT NULL CHECK (jsonb_typeof(station_snapshot)='object'),
    previous_hash text NOT NULL CHECK (previous_hash ~ '^[0-9a-f]{64}$'),
    event_hash text NOT NULL UNIQUE CHECK (event_hash ~ '^[0-9a-f]{64}$'),
    UNIQUE (station_id,version),
    CHECK (station_snapshot - ARRAY['id','name','brand','address','province_code','latitude','longitude','status','version','license_ref','duplicate_of'] = '{}'::jsonb)
);
CREATE TABLE of_public.price_observations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    station_id uuid NOT NULL REFERENCES of_public.stations(id),
    grade text NOT NULL CHECK (grade IN ('regular','midgrade','premium','diesel')),
    payment_condition text NOT NULL CHECK (payment_condition IN ('standard','cash','credit','membership')),
    membership_program text,
    price_milli_cad_per_litre integer NOT NULL CHECK (price_milli_cad_per_litre BETWEEN 500 AND 9999),
    observed_bucket timestamptz NOT NULL,
    provenance_kind text NOT NULL CHECK (provenance_kind IN ('community','operator','licensed_feed')),
    license_ref text NOT NULL,
    retracted boolean NOT NULL DEFAULT false,
    CHECK (observed_bucket = date_bin(interval '15 minutes',observed_bucket,'2000-01-01'::timestamptz)),
    CHECK ((payment_condition='membership') = (membership_program IS NOT NULL))
);
CREATE INDEX price_latest ON of_public.price_observations(station_id,grade,payment_condition,observed_bucket DESC);
-- The publication service must validate policy, serialize ledger appends, compare the station
-- version, canonicalize+hash the public payload, and write station/event atomically.
-- This trigger only blocks ordinary mutation; a superuser can bypass it. Independent retained
-- signed checkpoints are needed to detect operator rewrites or forked histories.
CREATE FUNCTION of_private.reject_event_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Public station events are append-only; publish a compensating event'; END $$;
CREATE TRIGGER station_event_append_only BEFORE UPDATE OR DELETE ON of_public.station_events
FOR EACH ROW EXECUTE FUNCTION of_private.reject_event_mutation();
-- Default deny on every table. No front-end API key can publish station changes.
ALTER TABLE of_public.stations ENABLE ROW LEVEL SECURITY;
ALTER TABLE of_public.station_sources ENABLE ROW LEVEL SECURITY;
ALTER TABLE of_public.station_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE of_public.price_observations ENABLE ROW LEVEL SECURITY;
ALTER TABLE of_private.station_proposals ENABLE ROW LEVEL SECURITY;
ALTER TABLE of_private.attestations ENABLE ROW LEVEL SECURITY;
ALTER TABLE of_private.moderation_decisions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON ALL TABLES IN SCHEMA of_public FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA of_private FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA of_private FROM PUBLIC;
-- Wire public READ ONLY grants/policies for actual application roles in a reviewed deployment migration.
COMMIT;
-- Example spatial read, issued through a privacy-preserving region/snapshot API:
-- SELECT id,name FROM of_public.stations
-- WHERE status='active' AND ST_DWithin(location,ST_SetSRID(ST_Point(-75.7,45.42),4326)::geography,5000);
