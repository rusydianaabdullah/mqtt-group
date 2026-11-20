# Store listing — English (en-US)

## App name (30 chars max)

```
Family Group: Chat & Map
```

(24 characters.)

## Short description (80 chars max)

```
Private group chat and a live map on your own MQTT broker. No accounts, no ads.
```

(79 characters.)

## Full description (4000 chars max)

Play accepts a little formatting in the full description: `<b>`, `<i>`, `<u>`, `<br>` and
simple lists. The version below uses plain paragraphs and dashes, which survive every renderer.

```
Family Group is a chat and location app for a small group of people who already trust each
other — a family, a household, a few friends. It has no accounts, no sign-up, no ads, and no
server of ours anywhere in the picture.

Instead it talks to an MQTT broker that you choose. Point it at a public broker to try it, or
run Mosquitto or EMQX on a machine you own and nothing about your group touches anyone else's
infrastructure.

<b>Nobody can read your messages, including the broker</b>
Every message, photo, voice note and position is encrypted on your phone with a key that exists
only on the phones in the group. The broker moves sealed bytes it cannot open. The key is
generated when you create a group and leaves only inside the invite QR code, shown in person.

<b>No accounts</b>
There is nothing to sign up for and no phone number to give. Your identity is a random id made
on first launch. You join a group by scanning a code from someone already in it.

<b>Chat</b>
Text, photos and voice notes, with delivery and read state. Anything sent while you are
offline is queued on the phone and goes out when the connection comes back.

<b>A map, if the group wants one</b>
Location sharing is off until the group's admin turns it on, and then it is still off for you
until you switch it on yourself — nobody can turn it on for you. When it is on, the map shows
where everyone is, with a history you can replay: pick a date range, watch the group's paths
draw themselves, and delete anyone's stored history from your phone whenever you like.

The admin sets how often each phone takes a position and how often it sends one, so a group can
choose between a detailed map and a longer battery life.

<b>Your data stays on your phone</b>
Messages, photos and positions are stored in an encrypted database on the device. There is no
cloud copy. An encrypted backup file, protected by a passphrase you choose, is there for moving
to a new phone.

<b>More than one broker</b>
Each group names its own broker, so a family on a home server and a group of friends on a
public one can sit side by side in the same app without either server seeing the other.

<b>Permissions, and why</b>
- Internet: to reach the broker you configure. Nothing is sent anywhere else.
- Location: only if your group has the map switched on and you have chosen to share. Positions
  go to your group, encrypted, through your broker. Sharing continues while the app is closed,
  with a notification showing for as long as it does.
- Camera: to scan an invite QR code, and to take a photo to send.
- Microphone: to record a voice note.
- Notifications: to tell you a message arrived.
- Run at startup and battery exemption: to keep the connection alive so messages arrive when
  the app is closed. Both are optional and the app explains what turning them off costs.

<b>What this app is not</b>
It is not a replacement for a messenger with millions of users. There is no discovery, no
phone-number lookup, no group over about a dozen people. It is built for a group small enough
that everyone can scan one QR code in the same room.

<b>Where this is going next</b>
This app is a working prototype of a private communicator for enterprise closed systems — a
mining site, a plantation, a vessel, a plant, a construction project: places where the network
is your own, the outside internet is unreliable or deliberately absent, and messages and crew
positions must never leave the operator's infrastructure. The same design carries over: an MQTT
broker you host, end-to-end encryption the broker cannot open, no accounts, and a map that keeps
working when connectivity does not.

If you are building something along those lines, or would like this adapted to your own
operation, please get in touch — the Contact button on the app's About screen opens an inquiry
form.
```

## Notes for the Play Console form

- **Category**: Communication.
- **Tags**: chat, family, location sharing, MQTT, self-hosted.
- **Contains ads**: No. **In-app purchases**: No.
- **Target audience**: 18+, or 13+ at the youngest. A location-sharing app aimed at children
  pulls in Families policy, which is a different and much longer form.

### Data safety

Answer this from what the app actually does, not from what feels safest to tick:

- **Data collected by us**: none. There is no server of ours.
- **Data shared**: none, in Play's sense — a self-hosted broker the *user* configures is not a
  third party we hand data to. Say so in the free-text if the form allows it.
- **Data transferred off the device**: yes, and this is the honest part. Messages, photos, voice
  notes and (if enabled) precise location travel to the user's chosen MQTT broker. Declare
  **Location — precise**, **Messages**, **Photos and videos**, **Audio**, purpose "App
  functionality", and mark all of them **encrypted in transit**.
- **Data deletion**: the app has "delete all data" in Settings, which wipes the database, the
  blobs and the keys. Point the deletion question at that.

### Sensitive permission declarations

Both of these need a written justification, and both are the usual reason a first submission
comes back:

- **ACCESS_BACKGROUND_LOCATION** — required because the whole point of the map is that it keeps
  working when the app is closed. Play will want a video showing the in-app disclosure, the
  runtime prompt, and the feature working. The app already gates this behind a group-level
  switch and a per-user switch, which is the story to tell.
- **Foreground service types** (`location`, `dataSync`) — declared in the manifest and used for
  exactly what they say.

### Before you submit

- A **privacy policy URL** is required for the listing and again in Data Safety. For this app it
  is short and true: we collect nothing, we run no server, data goes only to the broker the user
  configures.
- The map currently draws **Google's tile endpoint** (`mt1-3.google.com/vt`), which is not the
  Maps Platform API and is against Google's terms outside a Google Maps product. This is fine
  for a private build and is a real risk on a Play listing. `MapStyle.openFreeMapStyleUrl` is
  the one-line switch back to OpenStreetMap vector tiles, and is what should ship publicly.
