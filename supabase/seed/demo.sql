-- SPDX-License-Identifier: AGPL-3.0-only
-- LOCAL RESET ONLY. Not a migration. Never use `db push --include-seed` for production.
-- Fictional stations/prices, original independent fixture CC0-1.0.
SELECT app_private.publish_station('10000000-0000-4000-8000-000000000001',0,'Demo River Fuel','Demo','10 Example Avenue','demo-region',53.544,-113.491,'verified','CC0-1.0','openfuel:synthetic-fixture',true,'initial_verification');
SELECT app_private.publish_station('10000000-0000-4000-8000-000000000002',0,'Demo Prairie Fuel','Demo','20 Example Road','demo-region',53.555,-113.480,'verified','CC0-1.0','openfuel:synthetic-fixture',true,'initial_verification');
SELECT app_private.publish_price('10000000-0000-4000-8000-000000000001','regular','standard',1429,now()-interval '30 minutes','synthetic_fixture');
SELECT app_private.publish_price('10000000-0000-4000-8000-000000000002','regular','standard',1479,now()-interval '45 minutes','synthetic_fixture');
