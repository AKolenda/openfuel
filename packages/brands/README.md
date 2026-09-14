# Remote station brand images

`catalog.json` contains URL metadata and reviewed name aliases, not logo files.
The live API adds `brandKey`, `brandLogoUrl` and `brandLogoSourceUrl` to stations.
Unrecognized brands receive null fields and clients keep a readable initials fallback.

Images load directly from Wikimedia's thumbnail service or the brand's own site.
OpenFuel does not proxy these images, save them in D1, bundle them in an APK, or
copy them into its source archive. Browsers may use their normal HTTP cache;
native clients use bounded, disposable device caches to avoid fetching the same
brand again for every station. The web service worker does not cache logos.

The catalog covers 15 common brands, including Tempo added from its official site on 2026-09-14. The initial 14-brand catalog was checked on 2026-09-12. The Co-op and Shell images are their
official site's PNG icons; other entries link to the identified logo's Wikimedia
Commons source page. All catalog images were checked as PNG responses on
2026-09-12. Links can change or fail, and they are not a hosted-service guarantee.
Only reviewed HTTPS image hosts in `imageHosts` are allowed by clients.

Brand names and logos identify mapped businesses. They remain their owners'
trademarks and are not part of OpenFuel's AGPL code or OSM data licence. Their
presence does not imply sponsorship, affiliation, or endorsement. Each source
page records information about the referenced image. Adding a brand requires
reviewing its image/source URL, aliases, and client host allowlists.

Image providers receive network metadata when an image is requested. Requests
contain a brand image URL, not a user's coordinates or station-search query.
Browsers use `no-referrer` for brand images. Neither automatic scraping of
arbitrary station websites nor third-party JavaScript is used for logos.
