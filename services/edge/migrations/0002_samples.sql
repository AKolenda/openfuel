-- SPDX-License-Identifier: AGPL-3.0-only
-- Synthetic map reference seed. Applied only once through D1 migrations.
INSERT INTO stations(id, sort_order, data) VALUES ('parkside', 0, '{"id":"parkside","brand":"petro-canada","name":"Petro-Canada","address":"128 Parkway","area":"Old Town","distance":2.4,"minutes":6,"x":620,"y":462,"age":7,"open":true,"amenities":["store","coffee","wash"],"prices":{"regular":1429,"premium":1639,"diesel":1529},"distanceMetres":2400,"synthetic":true}');
INSERT INTO current_prices VALUES ('parkside', 'regular', 1429, strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-7 minutes'), 'sample');
INSERT INTO current_prices VALUES ('parkside', 'premium', 1639, strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-7 minutes'), 'sample');
INSERT INTO current_prices VALUES ('parkside', 'diesel', 1529, strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-7 minutes'), 'sample');
INSERT INTO stations(id, sort_order, data) VALUES ('northline', 1, '{"id":"northline","brand":"shell","name":"Shell","address":"640 Cedar Avenue","area":"Sunnyside","distance":1.2,"minutes":3,"x":820,"y":258,"age":12,"open":true,"amenities":["store","wash"],"prices":{"regular":1479,"premium":1689,"diesel":1549},"distanceMetres":1200,"synthetic":true}');
INSERT INTO current_prices VALUES ('northline', 'regular', 1479, strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-12 minutes'), 'sample');
INSERT INTO current_prices VALUES ('northline', 'premium', 1689, strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-12 minutes'), 'sample');
INSERT INTO current_prices VALUES ('northline', 'diesel', 1549, strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-12 minutes'), 'sample');
INSERT INTO stations(id, sort_order, data) VALUES ('juniper', 2, '{"id":"juniper","brand":"husky","name":"Husky (legacy)","address":"82 Birch Street","area":"Old Town","distance":0.8,"minutes":2,"x":425,"y":319,"age":18,"open":true,"amenities":["store","coffee"],"prices":{"regular":1499,"premium":1729,"diesel":null},"distanceMetres":800,"synthetic":true}');
INSERT INTO current_prices VALUES ('juniper', 'regular', 1499, strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-18 minutes'), 'sample');
INSERT INTO current_prices VALUES ('juniper', 'premium', 1729, strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-18 minutes'), 'sample');
INSERT INTO stations(id, sort_order, data) VALUES ('westway', 3, '{"id":"westway","brand":"esso","name":"Esso","address":"905 River Road","area":"South Common","distance":3.1,"minutes":7,"x":780,"y":786,"age":42,"open":true,"amenities":["store","coffee","wash"],"prices":{"regular":1529,"premium":1759,"diesel":1589},"distanceMetres":3100,"synthetic":true}');
INSERT INTO current_prices VALUES ('westway', 'regular', 1529, strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-42 minutes'), 'sample');
INSERT INTO current_prices VALUES ('westway', 'premium', 1759, strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-42 minutes'), 'sample');
INSERT INTO current_prices VALUES ('westway', 'diesel', 1589, strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-42 minutes'), 'sample');
INSERT INTO stations(id, sort_order, data) VALUES ('cedar', 4, '{"id":"cedar","brand":"mobil","name":"Mobil","address":"310 Lakeside Road","area":"Ridgeview","distance":4.2,"minutes":9,"x":247,"y":659,"age":180,"open":true,"amenities":["store"],"memberDiscount":40,"prices":{"regular":1449,"premium":1669,"diesel":1519},"distanceMetres":4200,"synthetic":true}');
INSERT INTO current_prices VALUES ('cedar', 'regular', 1449, strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-180 minutes'), 'sample');
INSERT INTO current_prices VALUES ('cedar', 'premium', 1669, strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-180 minutes'), 'sample');
INSERT INTO current_prices VALUES ('cedar', 'diesel', 1519, strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-180 minutes'), 'sample');
INSERT INTO stations(id, sort_order, data) VALUES ('loop', 5, '{"id":"loop","brand":"chevron","name":"Chevron","address":"18 Eastbank Drive","area":"Eastbank","distance":3.4,"minutes":8,"x":960,"y":543,"age":24,"open":false,"amenities":["store","coffee"],"prices":{"regular":1559,"premium":1799,"diesel":1629},"distanceMetres":3400,"synthetic":true}');
INSERT INTO current_prices VALUES ('loop', 'regular', 1559, strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-24 minutes'), 'sample');
INSERT INTO current_prices VALUES ('loop', 'premium', 1799, strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-24 minutes'), 'sample');
INSERT INTO current_prices VALUES ('loop', 'diesel', 1629, strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-24 minutes'), 'sample');
