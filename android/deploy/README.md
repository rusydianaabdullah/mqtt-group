# Play Store assets

Everything the Play Console asks for on a first submission. See `../RELEASE.md` for the build
and signing side.

```
deploy/
  listing/en-US.md                     app name, short + full description, console answers
  listing/id-ID.md                     the same, in Bahasa Indonesia
  listing/location-declaration.md      answers for the Location permissions form
  listing/foreground-service-declaration.md   answers for the Foreground service form
  videos/data-sync.mp4                 demonstration for FOREGROUND_SERVICE_DATA_SYNC
  videos/location.mp4                  demonstration for FOREGROUND_SERVICE_LOCATION
  graphics/icon-512.png                512×512 app icon
  graphics/feature-graphic-1024x500.png
  graphics/*.svg                       the sources both PNGs are rendered from
  screenshots/phone/                   1080×2160
  screenshots/tablet-7/                1200×1920
  screenshots/tablet-10/               1600×2560
  capture.sh                           drives the screenshots from an emulator
```

## Screenshots

`screenshots/phone/` holds seven 1080×2160 captures, taken from a real emulator running a real
group against a real broker — not mockups:

```
01-groups     the group list, connected
02-chat       a two-way conversation with read receipts
03-map        the live map, Google tiles, a member pinned
04-history    history mode: the range chip and the playback transport
05-settings   profile, About, connection, map layer
06-about      the About hero
07-enterprise the "what this becomes next" card and Contact
```

Upload them in that order — the first two are what most people see.

**The tablet sizes are still empty.** They are the same emulator resized (`capture.sh tablet-7`,
`capture.sh tablet-10`), and Play does not require them unless you are targeting tablets.

Re-taking the phone set is not a one-command job, and it is worth knowing why before you try.
adzan-time's `capture.sh` works from a fresh install because that app needs only a latitude and
a longitude. This one needs a broker, a group, **another member**, and traffic — and a second
member means a second device. The set above was produced by pairing two emulators: one created
the group, the other joined by pasting the invite code (the QR was decoded out of a screenshot,
since one emulator cannot photograph another's screen).

Two things that will waste an hour if you do not know them:

- **`adb emu geo fix` does not reach the app.** The app asks `LocationManager` for
  `FUSED_PROVIDER`, and on a Google-APIs image that is GMS's fused provider, which ignores the
  emulator's emulated GPS. Positions only started flowing through the test-provider API:
  `adb shell appops set com.android.shell android:mock_location allow`, then
  `cmd location providers add-test-provider fused`, `set-test-provider-enabled fused true`, and
  `set-test-provider-location fused --location <lat>,<lon>` repeatedly. A test provider does not
  survive an emulator restart, and re-adding it is silent when it fails.
- **A stray fix at (0,0) ruins every map shot.** "Show everyone" fits the camera to every known
  position, so one bogus row at the equator zooms the map out to the whole world. Delete the
  group's location history and push a fresh local track before capturing.

## Graphics

Both PNGs are rendered from the SVGs beside them, which are in turn the same geometry as the
launcher icon in `app/src/main/res/drawable/ic_launcher_foreground.xml`. To re-render after a
change:

```bash
python3 -c "import cairosvg; cairosvg.svg2png(url='deploy/graphics/icon.svg', \
  write_to='deploy/graphics/icon-512.png', output_width=512, output_height=512)"
```

`icon-512.png` is a full square with no transparency and no rounded corners — Play draws its own
mask, and pre-applying one leaves a visible double-rounded edge.

## Still to produce by hand

- **Privacy policy URL.** Required for the listing and again in Data Safety. For this app the
  honest version is short: we collect nothing, we run no server, and data goes only to the MQTT
  broker the user configures.
- **Upload the two videos.** `videos/*.mp4` are recorded; Play wants YouTube links, so upload
  each as unlisted and paste the URL into the Foreground service permissions form. Note the
  correction in `listing/foreground-service-declaration.md` about which Data sync box to tick.
- **The Location permissions declaration.** `listing/location-declaration.md` has every field
  paste-ready. `ACCESS_BACKGROUND_LOCATION` is not declared, so no demonstration video is
  needed — that field on the form exists to verify a background-location claim, and this app
  makes none.

## Two things to settle before submitting

**The map draws Google's tile endpoint.** `MapStyle.styleJson` points at `mt1-3.google.com/vt`,
which is Google's internal endpoint and not the Maps Platform API. Using it outside a Google
Maps product is against Google's terms — fine for a private build among family, a real risk on a
public listing, and doubly awkward when the listing lives on Google Play.
`MapStyle.openFreeMapStyleUrl` is the one-line switch back to OpenStreetMap vector tiles, and is
what should ship publicly.

**`versionCode` is still 1 and `versionName` is `0.1`.** Play accepts that for a first upload
and then never lets that code be used again, so decide deliberately rather than shipping the
placeholder.
