import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator, FlatList, KeyboardAvoidingView, Linking, Modal, Platform,
  Pressable, ScrollView, StyleSheet, Text, TextInput, View,
} from 'react-native';
import { OpenMap, type OpenMapHandle, type Region } from './src/OpenMap';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import Ionicons from '@expo/vector-icons/Ionicons';
import { StatusBar } from 'expo-status-bar';
import * as Location from 'expo-location';
import * as Crypto from 'expo-crypto';
import { fetchStations, searchCities, submitReport } from './src/api';
import { type Coordinates, type Fuel, type Station, fuels, parseCents, priceLabel, reportAge, sortedStations, validateResponse } from './src/domain';

const C = { green: '#245A43', pale: '#F0F5EA', muted: '#647366', ink: '#183328', white: '#FFFFFF', border: '#DCE5DB', red: '#C64032' };
const KEYS = { cache: 'openfuel.live.stations.v1', favorites: 'openfuel.favorites.v1', client: 'openfuel.installation.v1' };
const CITIES = [
  { name: 'Edmonton', latitude: 53.5461, longitude: -113.4938 },
  { name: 'Calgary', latitude: 51.0447, longitude: -114.0719 },
  { name: 'Vancouver', latitude: 49.2827, longitude: -123.1207 },
  { name: 'Toronto', latitude: 43.6532, longitude: -79.3832 },
  { name: 'Ottawa', latitude: 45.4215, longitude: -75.6972 },
  { name: 'Montréal', latitude: 45.5019, longitude: -73.5674 },
  { name: 'Winnipeg', latitude: 49.8951, longitude: -97.1384 },
  { name: 'Halifax', latitude: 44.6488, longitude: -63.5752 },
];
const CANADA: Region = { latitude: 55, longitude: -104, latitudeDelta: 30, longitudeDelta: 50 };
const closeRegion = (point: Coordinates): Region => ({ ...point, latitudeDelta: 0.09, longitudeDelta: 0.09 });
const distance = (metres: number) => Number.isFinite(metres) ? `${(metres / 1000).toFixed(1)} km` : '';
const title = (fuel: Fuel) => fuel[0].toUpperCase() + fuel.slice(1);
const failure = (error: unknown) => error instanceof Error ? error.message : 'Could not connect. Try again.';

type IconName = React.ComponentProps<typeof Ionicons>['name'];
function Action({ label, icon, onPress, primary, disabled }: { label: string; icon?: IconName; onPress: () => void; primary?: boolean; disabled?: boolean }) {
  return <Pressable accessibilityRole="button" accessibilityLabel={label} disabled={disabled} onPress={onPress}
    style={({ pressed }) => [styles.action, primary && styles.primary, (disabled || pressed) && { opacity: 0.6 }]}>
    {icon && <Ionicons name={icon} size={18} color={primary ? C.white : C.green} />}
    <Text style={[styles.actionLabel, primary && { color: C.white }]}>{label}</Text>
  </Pressable>;
}

function Sheet({ visible, onClose, children }: { visible: boolean; onClose: () => void; children: React.ReactNode }) {
  return <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}>
    <KeyboardAvoidingView style={styles.modal} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <Pressable accessibilityRole="button" accessibilityLabel="Close sheet" style={styles.scrim} onPress={onClose} />
      <SafeAreaView edges={['bottom']} style={styles.sheet}>
        <View style={styles.sheetHandle} />
        <Pressable accessibilityRole="button" accessibilityLabel="Close sheet" onPress={onClose} style={styles.close}>
          <Ionicons name="close" size={24} color={C.ink} />
        </Pressable>
        {children}
      </SafeAreaView>
    </KeyboardAvoidingView>
  </Modal>;
}

function OpenFuel() {
  const map = useRef<OpenMapHandle>(null);
  const loadSequence = useRef(0);
  const clientId = useRef('');
  const currentArea = useRef<{ point: Coordinates; label: string } | null>(null);
  const requestIdentity = useRef<{ key: string; id: string } | null>(null);
  const [stations, setStations] = useState<Station[]>([]);
  const [fuel, setFuel] = useState<Fuel>('regular');
  const [sort, setSort] = useState<'distance' | 'price'>('distance');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [onlySaved, setOnlySaved] = useState(false);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [locating, setLocating] = useState(false);
  const [permission, setPermission] = useState(false);
  const [deviceLocation, setDeviceLocation] = useState<Coordinates | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [areaLabel, setAreaLabel] = useState('Choose an area');
  const [coverage, setCoverage] = useState('');
  const [savedAt, setSavedAt] = useState<string | null>(null);
  const [offline, setOffline] = useState(false);
  const [region, setRegion] = useState<Region>(CANADA);
  const [mapMoved, setMapMoved] = useState(false);
  const [showCities, setShowCities] = useState(false);
  const [cityQuery, setCityQuery] = useState('');
  const [cityResults, setCityResults] = useState(CITIES);
  const [cityError, setCityError] = useState('');
  const [selected, setSelected] = useState<Station | null>(null);
  const [reportStation, setReportStation] = useState<Station | null>(null);
  const [reportFuel, setReportFuel] = useState<Fuel>('regular');
  const [price, setPrice] = useState('');
  const [observed, setObserved] = useState(false);
  const [sending, setSending] = useState(false);
  const [reportError, setReportError] = useState('');

  const loadArea = useCallback(async (point: Coordinates, label: string, animate = true) => {
    const sequence = ++loadSequence.current;
    setLoading(true); setError(''); setCoverage(''); setMapMoved(false);
    const previous = currentArea.current?.point;
    if (!previous || Math.abs(previous.latitude - point.latitude) > 0.01 || Math.abs(previous.longitude - point.longitude) > 0.01) {
      setStations([]); setSavedAt(null); setSelected(null);
    }
    currentArea.current = { point, label };
    setAreaLabel(label);
    if (animate) map.current?.animateToRegion(closeRegion(point), 450);
    try {
      const data = await fetchStations(point);
      if (sequence !== loadSequence.current) return;
      const timestamp = new Date().toISOString();
      setStations(data.stations); setOffline(false); setSavedAt(timestamp);
      setCoverage(typeof data.coverage?.message === 'string' ? data.coverage.message : '');
      AsyncStorage.setItem(KEYS.cache, JSON.stringify({ data, point, label, timestamp })).catch(() => setNotice('Stations loaded, but offline storage is unavailable.'));
    } catch (cause) {
      if (sequence !== loadSequence.current) return;
      setOffline(true); setError(failure(cause));
      // A different area's old prices must never look like results for this location.
      try {
        const value = await AsyncStorage.getItem(KEYS.cache);
        if (sequence !== loadSequence.current) return;
        const cache = value ? JSON.parse(value) : null;
        if (cache && Math.abs(cache.point.latitude - point.latitude) < 0.01 && Math.abs(cache.point.longitude - point.longitude) < 0.01) {
          setStations(validateResponse(cache.data).stations); setSavedAt(cache.timestamp);
        } else { setStations([]); setSavedAt(null); }
      } catch { if (sequence === loadSequence.current) { setStations([]); setSavedAt(null); } }
    } finally {
      if (sequence === loadSequence.current) setLoading(false);
    }
  }, []);

  const locate = useCallback(async () => {
    setLocating(true); setError(''); setNotice('');
    try {
      const result = await Location.requestForegroundPermissionsAsync();
      setPermission(result.granted);
      if (!result.granted) setDeviceLocation(null);
      if (!result.granted) {
        setNotice('Location is off. Choose a city or move the map to find stations. You can enable location in Settings.');
        return;
      }
      if (!await Location.hasServicesEnabledAsync()) {
        setNotice('Turn on your phone’s location service, or choose an area on the map.');
        return;
      }
      let timer: ReturnType<typeof setTimeout> | undefined;
      const position = await Promise.race([
        Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced }),
        new Promise<never>((_, reject) => { timer = setTimeout(() => reject(new Error('Could not get a GPS fix. Try outside or choose a city.')), 20_000); }),
      ]).finally(() => clearTimeout(timer));
      setDeviceLocation(position.coords);
      await loadArea(position.coords, 'Near your location');
    } catch (cause) { setNotice(failure(cause)); }
    finally { setLocating(false); }
  }, [loadArea]);

  useEffect(() => {
    let disposed = false;
    (async () => {
      try {
        const values = await AsyncStorage.multiGet([KEYS.client, KEYS.favorites, KEYS.cache]);
        if (disposed) return;
        clientId.current = values[0][1] || Crypto.randomUUID();
        if (!values[0][1]) await AsyncStorage.setItem(KEYS.client, clientId.current);
        const saved: unknown = JSON.parse(values[1][1] || '[]');
        setFavorites(Array.isArray(saved) ? saved.filter((id): id is string => typeof id === 'string') : []);
        if (values[2][1]) {
          const cache = JSON.parse(values[2][1]);
          const data = validateResponse(cache.data);
          setStations(data.stations); setAreaLabel(`Last area: ${cache.label}`); setSavedAt(cache.timestamp); setOffline(true);
          currentArea.current = { point: cache.point, label: cache.label };
          setRegion(closeRegion(cache.point));
          map.current?.animateToRegion(closeRegion(cache.point), 1);
        }
      } catch { clientId.current ||= Crypto.randomUUID(); }
      if (!disposed) void locate();
    })();
    return () => { disposed = true; };
  }, [locate]);

  useEffect(() => {
    let active = true;
    if (cityQuery.trim().length < 2) { setCityResults(CITIES); setCityError(''); return; }
    const timer = setTimeout(() => {
      searchCities(cityQuery).then(results => { if (active) { setCityResults(results); setCityError(results.length ? '' : 'No matching cities. Try a nearby city, then move the map.'); } })
        .catch(() => { if (active) { setCityResults(CITIES.filter(city => city.name.toLowerCase().includes(cityQuery.toLowerCase()))); setCityError('City search is unavailable. Choose a city below or move the map.'); } });
    }, 350);
    return () => { active = false; clearTimeout(timer); };
  }, [cityQuery]);

  const visibleStations = useMemo(() => sortedStations(stations.filter(station =>
    (!onlySaved || favorites.includes(station.id)) &&
    `${station.name} ${station.brand} ${station.address}`.toLowerCase().includes(query.trim().toLowerCase())), fuel, sort),
  [stations, onlySaved, favorites, query, fuel, sort]);

  const toggleFavorite = (station: Station) => {
    const next = favorites.includes(station.id) ? favorites.filter(id => id !== station.id) : [...favorites, station.id];
    setFavorites(next);
    AsyncStorage.setItem(KEYS.favorites, JSON.stringify(next)).catch(() => setNotice('Could not save favorites on this device.'));
  };
  const beginReport = (station: Station) => {
    setSelected(null); setReportStation(station); setReportFuel(fuel); setPrice(''); setObserved(false); setReportError('');
    requestIdentity.current = null;
  };
  const send = async () => {
    const milli = parseCents(price);
    if (!reportStation || milli === null || !observed || sending) return;
    setSending(true); setReportError('');
    const key = `${reportStation.id}:${reportFuel}:${milli}`;
    if (requestIdentity.current?.key !== key) requestIdentity.current = { key, id: Crypto.randomUUID() };
    try {
      await submitReport({ station_id: reportStation.id, fuel_type: reportFuel, price_milli: milli, client_id: clientId.current, request_id: requestIdentity.current.id });
      setReportStation(null); setNotice('Price shared. Community reports are unverified.');
      if (currentArea.current) await loadArea(currentArea.current.point, currentArea.current.label, false);
    } catch (cause) { setReportError(failure(cause)); }
    finally { setSending(false); }
  };
  const navigate = (station: Station) => {
    const url = `https://www.google.com/maps/dir/?api=1&destination=${station.latitude},${station.longitude}&travelmode=driving`;
    Linking.openURL(url).catch(() => setNotice('Could not open directions on this device.'));
  };

  return <SafeAreaView style={styles.screen} edges={['top', 'left', 'right', 'bottom']}>
    <StatusBar style="dark" />
    <View style={styles.header}>
      <View><Text style={styles.wordmark}>openfuel</Text><Pressable accessibilityRole="button" onPress={() => setShowCities(true)}><Text numberOfLines={1} style={styles.area}>{areaLabel} <Ionicons name="chevron-down" size={12} /></Text></Pressable></View>
      <Pressable accessibilityRole="button" accessibilityLabel="Use my location" onPress={() => void locate()} disabled={locating} style={styles.locationButton}>
        {locating ? <ActivityIndicator color={C.green} size="small" /> : <Ionicons name="locate" color={C.green} size={23} />}
      </Pressable>
    </View>
    <View style={styles.fuels}>{fuels.map(item => <Pressable key={item} accessibilityRole="button" accessibilityState={{ selected: fuel === item }} onPress={() => setFuel(item)} style={[styles.fuel, item === fuel && styles.fuelActive]}><Text style={[styles.fuelText, item === fuel && styles.fuelTextActive]}>{title(item)}</Text></Pressable>)}<Text style={styles.unit}>¢ / L</Text></View>

    <View style={styles.mapContainer}>
      <OpenMap ref={map} initialRegion={region} stations={visibleStations} fuel={fuel} location={deviceLocation}
        onSelect={setSelected} onRegionChangeComplete={setRegion} onPanDrag={() => setMapMoved(true)} />
      {mapMoved && <View style={styles.searchArea}><Action label="Search this area" icon="search" onPress={() => void loadArea(region, 'Map area', false)} primary /></View>}
      {!currentArea.current && !locating && <View style={styles.mapIntro}><Text style={styles.introTitle}>Find fuel around you.</Text><Text style={styles.body}>Use your location or choose a city to load real stations.</Text><Action label="Choose a city" icon="map-outline" onPress={() => setShowCities(true)} primary /></View>}
      {loading && <View style={styles.loadingMap}><ActivityIndicator size="small" color={C.green} /><Text style={styles.body}>Finding stations…</Text></View>}
    </View>

    <View style={styles.stationPanel}>
      {(notice || error || offline) ? <View style={styles.notice}><Text style={styles.noticeText} accessibilityLiveRegion="polite">{error || notice || 'Showing saved stations.'}{offline && savedAt ? ` Saved ${reportAge(savedAt).toLowerCase()}.` : ''}</Text>{!permission && !!notice && <Pressable accessibilityRole="button" onPress={() => void Linking.openSettings()}><Text style={styles.textLink}>Settings</Text></Pressable>}</View> : null}
      <View style={styles.searchRow}>
        <View style={styles.search}><Ionicons name="search" color={C.muted} size={17} /><TextInput value={query} onChangeText={setQuery} placeholder="Search nearby stations" placeholderTextColor={C.muted} style={styles.searchInput} accessibilityLabel="Search nearby stations" /></View>
        <Pressable accessibilityRole="button" accessibilityLabel={onlySaved ? 'Show all stations' : 'Show saved stations'} accessibilityState={{ selected: onlySaved }} onPress={() => setOnlySaved(!onlySaved)} style={[styles.favoriteFilter, onlySaved && { backgroundColor: C.pale }]}><Ionicons name={onlySaved ? 'heart' : 'heart-outline'} color={C.green} size={23} /></Pressable>
      </View>
      <View style={styles.listHeading}><Text style={styles.listTitle}>{visibleStations.length} {onlySaved ? 'saved' : 'nearby'} stations</Text><Pressable accessibilityRole="button" onPress={() => setSort(sort === 'distance' ? 'price' : 'distance')}><Text style={styles.sort}>{sort === 'distance' ? 'Nearest first' : 'Lowest price'} <Ionicons name="swap-vertical" size={13} /></Text></Pressable></View>
      <FlatList data={visibleStations} keyExtractor={item => item.id} keyboardShouldPersistTaps="handled" refreshing={loading}
        onRefresh={() => { if (currentArea.current) void loadArea(currentArea.current.point, currentArea.current.label, false); else void locate(); }}
        contentContainerStyle={visibleStations.length ? undefined : { flexGrow: 1 }}
        ListEmptyComponent={<View style={styles.empty}><Text style={styles.emptyTitle}>{loading ? 'Looking nearby…' : onlySaved ? 'Save your usual stops.' : query ? 'No matching stations.' : currentArea.current ? 'No stations loaded here.' : 'Your next fill starts here.'}</Text><Text style={styles.body}>{loading ? 'The first visit to an area can take a moment.' : onlySaved ? 'Tap the heart on a station to keep it handy.' : coverage || (error ? 'Pull down to retry, or choose another area.' : 'Choose a city or search another area on the map.')}</Text></View>}
        renderItem={({ item }) => <Pressable accessibilityRole="button" accessibilityLabel={`${item.name}, ${item.prices[fuel] === null ? 'no price reported' : priceLabel(item.prices[fuel]) + ' cents per litre'}`} onPress={() => setSelected(item)} style={styles.stationRow}>
          <View style={styles.stationInfo}><Text style={styles.stationName} numberOfLines={1}>{item.name}</Text><Text style={styles.address} numberOfLines={1}>{distance(item.distanceMetres)}{item.address ? ` · ${item.address}` : ''}</Text><Text style={styles.age}>{item.prices[fuel] === null ? 'Be the first to report' : `${reportAge(item.observedAt?.[fuel])} · Unverified`}</Text></View>
          <View style={styles.priceColumn}><Text style={[styles.price, item.prices[fuel] === null && styles.noPrice]}>{priceLabel(item.prices[fuel])}</Text><Text style={styles.priceCaption}>{item.prices[fuel] === null ? 'No report' : '¢ / L'}</Text></View>
          <Pressable accessibilityRole="button" accessibilityLabel={favorites.includes(item.id) ? `Unsave ${item.name}` : `Save ${item.name}`} onPress={() => toggleFavorite(item)} style={styles.heart}><Ionicons name={favorites.includes(item.id) ? 'heart' : 'heart-outline'} color={C.green} size={22} /></Pressable>
        </Pressable>} />
      <Text style={styles.footer}>Real stations. Prices come from people like you.</Text>
    </View>

    <Sheet visible={showCities} onClose={() => setShowCities(false)}>
      <Text style={styles.sheetTitle}>Where are you filling up?</Text><Text style={styles.body}>Choose a starting area, then move the map anywhere in Canada.</Text>
      <View style={styles.sheetActions}><Action label="Use my location" icon="locate" primary onPress={() => { setShowCities(false); void locate(); }} /></View>
      <View style={[styles.search, { flex: 0, marginBottom: 12 }]}><Ionicons name="search" size={17} color={C.muted} /><TextInput accessibilityLabel="Search Canadian cities" placeholder="Search Canadian cities" value={cityQuery} onChangeText={setCityQuery} style={styles.searchInput} placeholderTextColor={C.muted} /></View>
      {!!cityError && <Text style={styles.body}>{cityError}</Text>}
      <ScrollView keyboardShouldPersistTaps="handled" style={{ maxHeight: 300 }}>{cityResults.map(city => <Pressable accessibilityRole="button" key={`${city.name}:${city.latitude}`} onPress={() => { setShowCities(false); void loadArea(city, city.name); }} style={styles.city}><Ionicons name="location-outline" size={19} color={C.green} /><Text style={styles.cityText}>{city.name}</Text><Ionicons name="chevron-forward" size={18} color={C.muted} /></Pressable>)}</ScrollView>
      <Text style={styles.finePrint}>Location is used only while you use the app. Nearby searches send rounded coordinates to OpenFuel. Map providers receive map requests.</Text>
    </Sheet>

    <Sheet visible={selected !== null} onClose={() => setSelected(null)}>{selected && <>
      <Text style={styles.sheetTitle}>{selected.name}</Text><Text style={styles.body}>{selected.address || 'Address not listed'}{distance(selected.distanceMetres) ? ` · ${distance(selected.distanceMetres)} from search centre` : ''}</Text>
      <View style={styles.detailPrice}><Text style={styles.detailNumber}>{priceLabel(selected.prices[fuel])}</Text><View><Text style={styles.detailUnit}>{title(fuel)} · ¢ / L</Text><Text style={styles.body}>{selected.prices[fuel] === null ? 'No price reported yet' : reportAge(selected.observedAt?.[fuel])}</Text></View></View>
      <Text style={styles.body}>{selected.prices[fuel] === null ? 'Seen the price at the pump? Share it with the next driver.' : 'Community report, unverified. Confirm the price at the pump.'}</Text>
      <View style={styles.sheetActions}><Action label="Report price" icon="add" primary onPress={() => beginReport(selected)} /><Action label="Go" icon="navigate-outline" onPress={() => navigate(selected)} /><Action label={favorites.includes(selected.id) ? 'Saved' : 'Save'} icon={favorites.includes(selected.id) ? 'heart' : 'heart-outline'} onPress={() => toggleFavorite(selected)} /></View>
      <Text style={styles.finePrint}>Station details from OpenStreetMap contributors, ODbL. Station details may be incomplete or outdated.</Text>
    </>}</Sheet>

    <Sheet visible={reportStation !== null} onClose={() => { if (!sending) setReportStation(null); }}>{reportStation && <ScrollView keyboardShouldPersistTaps="handled">
      <Text style={styles.sheetTitle}>Share a pump price</Text><Text style={styles.body}>{reportStation.name}</Text>
      <View style={[styles.fuels, { paddingHorizontal: 0, marginTop: 18 }]}>{fuels.map(item => <Pressable accessibilityRole="button" key={item} onPress={() => setReportFuel(item)} style={[styles.fuel, reportFuel === item && styles.fuelActive]}><Text style={[styles.fuelText, reportFuel === item && styles.fuelTextActive]}>{title(item)}</Text></Pressable>)}</View>
      <Text style={styles.inputLabel}>Price in cents per litre</Text>
      <View style={styles.priceInputRow}><TextInput accessibilityLabel="Price in cents per litre" value={price} onChangeText={setPrice} placeholder="149.9" placeholderTextColor="#9AA99C" keyboardType="decimal-pad" maxLength={5} style={styles.priceInput} editable={!sending} /><Text style={styles.detailUnit}>¢ / L</Text></View>
      <Text style={styles.body}>Enter the price displayed at the pump, such as 149.9.</Text>
      <Pressable accessibilityRole="checkbox" accessibilityState={{ checked: observed }} onPress={() => setObserved(!observed)} style={styles.confirm}><Ionicons name={observed ? 'checkbox' : 'square-outline'} size={25} color={C.green} /><Text style={styles.confirmText}>I saw this price at this station today. It is the standard price, without a membership discount.</Text></Pressable>
      {!!reportError && <Text style={styles.reportError} accessibilityLiveRegion="polite">{reportError} Your report has not been confirmed. You can retry.</Text>}
      <Action label={sending ? 'Sharing…' : 'Share price'} icon="checkmark" primary disabled={parseCents(price) === null || !observed || sending} onPress={() => void send()} />
      <Text style={styles.finePrint}>This report becomes public. A random installation ID limits spam. Your GPS coordinates are not attached to the report.</Text>
    </ScrollView>}</Sheet>
  </SafeAreaView>;
}

export default function App() { return <SafeAreaProvider><OpenFuel /></SafeAreaProvider>; }

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: C.white },
  header: { paddingHorizontal: 20, paddingTop: 9, paddingBottom: 13, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  wordmark: { color: C.green, fontSize: 29, fontWeight: '800', letterSpacing: -1.3 },
  area: { color: C.muted, fontSize: 12, marginTop: 2, maxWidth: 270 },
  locationButton: { width: 44, height: 44, borderRadius: 22, backgroundColor: C.pale, alignItems: 'center', justifyContent: 'center' },
  fuels: { flexDirection: 'row', gap: 6, paddingHorizontal: 20, paddingBottom: 12, alignItems: 'center' },
  fuel: { borderRadius: 18, paddingHorizontal: 17, paddingVertical: 9, backgroundColor: C.pale },
  fuelActive: { backgroundColor: C.green }, fuelText: { color: C.green, fontSize: 13, fontWeight: '600' }, fuelTextActive: { color: C.white },
  unit: { marginLeft: 'auto', fontSize: 12, color: C.muted },
  mapContainer: { flex: 1, minHeight: 175, backgroundColor: C.pale },
  mapPin: { paddingHorizontal: 10, paddingVertical: 5, borderRadius: 20, borderColor: C.white, borderWidth: 2, backgroundColor: C.green, elevation: 3 },
  unknownPin: { backgroundColor: C.white, borderColor: C.green }, mapPrice: { color: C.white, fontSize: 13, fontWeight: '800' },
  searchArea: { position: 'absolute', top: 14, alignSelf: 'center' },
  mapIntro: { position: 'absolute', alignSelf: 'center', top: '15%', marginHorizontal: 30, padding: 20, gap: 12, backgroundColor: C.white, borderRadius: 18, elevation: 3, maxWidth: 360 },
  introTitle: { fontSize: 24, color: C.ink, fontWeight: '700', letterSpacing: -0.6 },
  loadingMap: { position: 'absolute', top: 14, alignSelf: 'center', flexDirection: 'row', gap: 8, borderRadius: 24, paddingHorizontal: 18, paddingVertical: 11, backgroundColor: C.white },
  mapCredit: { position: 'absolute', left: 0, right: 0, bottom: 0, alignItems: 'center', paddingVertical: 2, backgroundColor: '#FFFFFFDD' }, credit: { color: C.muted, fontSize: 9 },
  stationPanel: { flex: 0.95, minHeight: 225, backgroundColor: C.white },
  searchRow: { flexDirection: 'row', marginHorizontal: 16, marginTop: 13, gap: 8 }, search: { flexDirection: 'row', alignItems: 'center', flex: 1, backgroundColor: C.pale, paddingHorizontal: 12, borderRadius: 12, gap: 8 },
  searchInput: { flex: 1, height: 42, color: C.ink, fontSize: 13 }, favoriteFilter: { alignItems: 'center', justifyContent: 'center', width: 42, borderRadius: 12 },
  listHeading: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginHorizontal: 20, marginVertical: 12 }, listTitle: { fontSize: 14, fontWeight: '700', color: C.ink }, sort: { fontSize: 12, color: C.green, fontWeight: '500' },
  stationRow: { flexDirection: 'row', alignItems: 'center', paddingLeft: 20, paddingRight: 12, paddingVertical: 14, borderTopWidth: StyleSheet.hairlineWidth, borderColor: C.border, gap: 10 },
  stationInfo: { flex: 1 }, stationName: { color: C.ink, fontSize: 15, fontWeight: '700' }, address: { color: C.muted, fontSize: 11, marginTop: 4 }, age: { color: C.muted, fontSize: 10, marginTop: 6 },
  priceColumn: { alignItems: 'flex-end', minWidth: 58 }, price: { fontSize: 23, color: C.green, fontWeight: '800', letterSpacing: -0.8 }, noPrice: { color: C.muted }, priceCaption: { color: C.muted, fontSize: 9, marginTop: 2 }, heart: { padding: 9 },
  footer: { fontSize: 10, color: C.muted, textAlign: 'center', paddingVertical: 8, borderTopWidth: StyleSheet.hairlineWidth, borderColor: C.border },
  empty: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 25, gap: 8 }, emptyTitle: { fontSize: 17, fontWeight: '600', color: C.ink },
  body: { fontSize: 13, lineHeight: 19, color: C.muted }, notice: { backgroundColor: C.pale, paddingHorizontal: 18, paddingVertical: 9, flexDirection: 'row', alignItems: 'center', gap: 8 }, noticeText: { color: C.green, fontSize: 11, lineHeight: 16, flex: 1 }, textLink: { color: C.green, fontSize: 11, textDecorationLine: 'underline' },
  action: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 7, backgroundColor: C.pale, paddingHorizontal: 17, paddingVertical: 13, borderRadius: 24 }, primary: { backgroundColor: C.green }, actionLabel: { color: C.green, fontSize: 13, fontWeight: '600' },
  modal: { flex: 1, justifyContent: 'flex-end' }, scrim: { position: 'absolute', top: 0, right: 0, bottom: 0, left: 0, backgroundColor: '#10241966' }, sheet: { maxHeight: '88%', backgroundColor: C.white, borderTopLeftRadius: 26, borderTopRightRadius: 26, paddingHorizontal: 24, paddingBottom: 15, paddingTop: 12 },
  sheetHandle: { width: 38, height: 4, backgroundColor: C.border, borderRadius: 3, alignSelf: 'center', marginBottom: 26 }, close: { position: 'absolute', right: 14, top: 22, padding: 9 },
  sheetTitle: { color: C.ink, fontSize: 25, letterSpacing: -0.7, fontWeight: '700', marginBottom: 9, paddingRight: 28 }, sheetActions: { flexDirection: 'row', gap: 8, marginVertical: 22, flexWrap: 'wrap' },
  city: { flexDirection: 'row', gap: 12, alignItems: 'center', paddingVertical: 14, borderTopWidth: StyleSheet.hairlineWidth, borderColor: C.border }, cityText: { flex: 1, color: C.ink, fontSize: 15 }, finePrint: { fontSize: 11, lineHeight: 16, color: C.muted, marginTop: 15, marginBottom: 12 },
  detailPrice: { flexDirection: 'row', alignItems: 'center', gap: 20, marginVertical: 25 }, detailNumber: { color: C.green, fontWeight: '800', fontSize: 48, letterSpacing: -2 }, detailUnit: { fontSize: 13, color: C.green, fontWeight: '600', marginBottom: 4 },
  inputLabel: { color: C.ink, fontWeight: '600', fontSize: 13, marginTop: 12 }, priceInputRow: { flexDirection: 'row', alignItems: 'center', gap: 15, marginVertical: 12, borderBottomWidth: 1, borderColor: C.border }, priceInput: { color: C.green, fontSize: 45, fontWeight: '700', minWidth: 145, paddingVertical: 9 },
  confirm: { flexDirection: 'row', alignItems: 'center', gap: 12, marginVertical: 24 }, confirmText: { flex: 1, fontSize: 12, lineHeight: 18, color: C.ink }, reportError: { color: C.red, fontSize: 12, lineHeight: 18, marginBottom: 15 },
});
