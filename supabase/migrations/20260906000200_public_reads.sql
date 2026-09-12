-- SPDX-License-Identifier: AGPL-3.0-only
-- Public RPCs use narrow, explicit projections. No private-schema exposure in PostgREST.
BEGIN;
CREATE FUNCTION public.openfuel_stations_v1(p_region text DEFAULT 'demo-region',p_fuel text DEFAULT 'regular',p_payment text DEFAULT 'standard',p_limit integer DEFAULT 100,p_offset integer DEFAULT 0)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE entries jsonb; count_items integer;
BEGIN
 IF p_region IS NULL OR p_region !~ '^[a-z0-9-]{1,64}$' OR p_fuel IS NULL OR p_fuel NOT IN ('regular','midgrade','premium','diesel') OR p_payment IS NULL OR p_payment NOT IN ('standard','cash','credit','membership') OR p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 200 OR p_offset IS NULL OR p_offset NOT BETWEEN 0 AND 10000 THEN RAISE EXCEPTION 'Invalid query' USING ERRCODE='22023'; END IF;
 SELECT coalesce(jsonb_agg(entry ORDER BY sid),'[]'::jsonb),count(*) INTO entries,count_items FROM (
  SELECT s.id AS sid, jsonb_build_object(
    'id',s.id,'name',s.name,'brand',s.brand,'address',s.address,'region',s.region,
    'latitude_e6',round(extensions.st_y(s.location::extensions.geometry)*1000000)::bigint,
    'longitude_e6',round(extensions.st_x(s.location::extensions.geometry)*1000000)::bigint,
    'currency','CAD','volume_unit','L','is_demo',s.is_demo,'status',s.status,'version',s.version,'source_license',s.source_license,
    'price',CASE WHEN p.event_seq IS NULL OR s.status<>'verified' THEN NULL ELSE jsonb_build_object(
      'event_seq',p.event_seq,'price_milli',p.milli_cad_per_litre,'fuel_type',p.grade,'payment_type',p.payment,
      'currency','CAD','volume_unit','L','observed_bucket',to_char(p.observed_window AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"'),
      'age_seconds',greatest(0,floor(extract(epoch FROM now()-p.observed_window)))::bigint,
      'freshness',CASE WHEN now()-p.observed_window<=interval '1 hour' THEN 'recent' WHEN now()-p.observed_window<=interval '24 hours' THEN 'aging' ELSE 'stale' END,
      'source',p.source,'verification',CASE WHEN s.is_demo THEN 'demo' ELSE 'unverified' END
    ) END) AS entry
  FROM app_private.stations s
  LEFT JOIN LATERAL(SELECT * FROM app_private.price_observations o WHERE o.station_id=s.id AND o.grade=p_fuel AND o.payment=p_payment AND o.retracted_at_event IS NULL ORDER BY o.observed_window DESC,o.event_seq DESC LIMIT 1)p ON true
  WHERE s.region=p_region AND s.status IN ('verified','temporarily_closed')
  ORDER BY s.id LIMIT p_limit OFFSET p_offset
 ) published_rows;
 RETURN jsonb_build_object('region',p_region,'stations',entries,'next_cursor',CASE WHEN count_items=p_limit AND p_offset+p_limit<=10000 THEN p_offset+p_limit ELSE NULL END);
END $$;
CREATE FUNCTION public.openfuel_regions_v1() RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT jsonb_build_object('regions',coalesce(jsonb_agg(jsonb_build_object('id',region,'station_count',n) ORDER BY region),'[]'::jsonb))
 FROM (SELECT region,count(*) n FROM app_private.stations WHERE status IN ('verified','temporarily_closed') GROUP BY region ORDER BY region LIMIT 1000) r;
$$;
REVOKE ALL ON FUNCTION public.openfuel_stations_v1(text,text,text,integer,integer),public.openfuel_regions_v1() FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION public.openfuel_stations_v1(text,text,text,integer,integer),public.openfuel_regions_v1() TO anon,authenticated;
COMMENT ON FUNCTION public.openfuel_stations_v1 IS 'Versioned anonymous read API. Returns published business facts, never proposals, evidence, or contributor identities.';
COMMIT;
