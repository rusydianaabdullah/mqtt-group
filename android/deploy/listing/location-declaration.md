# Play Console — Location permissions declaration

For **App content → Sensitive app permissions → Location permissions**, which is the form that
produced "Your app uses the following undeclared Location permissions".

Everything below is paste-ready and true of the shipped code. Do not soften it: the reviewer
compares the words against the build, and a justification that describes a feature the app does
not have is worse than no justification.

---

## Background location is not declared

`ACCESS_BACKGROUND_LOCATION` was removed from the manifest. This form therefore covers
`ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` only, and **no demonstration video is
required** — Play asks for one to verify a background-location claim, and there is no longer a
claim to verify.

It was previously declared and never requested anywhere in the code, so it granted nothing and
cost the heaviest review Play has. Sharing continues while the app is closed regardless, because
it runs inside a foreground service declared `android:foregroundServiceType="location"` with a
permanent notification; such a service may use location for as long as it runs.

The one real consequence, worth knowing if a reviewer or a user asks: `BootReceiver` and
`ConnectionKeepAliveWorker` start the service from the background, and those restarts sync
messages but cannot read a position until the app is next opened.

---

## App purpose

The single free-text field Play asks for. 500-character limit; this is 466.

```
Family Group shows the members of one small private group where each other are on a shared map,
and lets them replay the last day's routes. Location is used for nothing else. It is off until
the group's admin turns it on, and still off for each member until they switch it on for
themselves; nobody can switch it on for someone else. Positions are encrypted on the device and
sent only to the MQTT broker the user configures. We run no server and never receive them.
```

## Which permissions, and why

**ACCESS_FINE_LOCATION** — the map's purpose is to show where a family member is closely enough
to be useful: which building, which junction. Coarse location can be a kilometre out, which
answers "roughly which city" and not "have they reached the school yet".

**ACCESS_COARSE_LOCATION** — requested alongside fine, and accepted on its own. A member who
grants only approximate location still appears on the map, less precisely. The app treats either
grant as sufficient (`MainActivity.setShare`) rather than refusing the feature.

**ACCESS_BACKGROUND_LOCATION** — not declared. See above.

## Is location access required for core functionality?

Yes for the map, no for the app. Chat, photos, voice notes and file sharing all work with
location permanently denied, and the app is fully usable that way — the Map tab simply explains
that sharing is off. Say this plainly; an app that overstates the dependency reads as one that
has not thought about it.

## Prominent disclosure

The app's disclosure is in `LocationSettingsScreen`, on the same card as the switch that starts
sharing, and it is shown before the runtime prompt because the prompt is only triggered by
touching that switch. The text a reviewer will see:

> **Share my location** — Sends your position every {interval} once you have moved {distance} m.
> Off, you still see everyone else.

Turning it on immediately raises a persistent notification for the foreground service, so the
phone is never sharing silently.

That is sufficient for the permissions this app declares. It would not be sufficient for
background location, which requires the disclosure to say that location is collected when the
app is closed or not in use — one more reason the permission is not declared.

## Data safety — keep these consistent

The Location permissions form and the Data safety form are cross-checked, and a mismatch is a
rejection on its own.

- **Collected**: Location (precise). Yes.
- **Shared**: No third party receives it. The broker is infrastructure the *user* chooses and
  usually runs; it is not a recipient we send data to.
- **Transferred off device**: Yes, to that broker, **encrypted in transit** and additionally
  end-to-end encrypted with a key the broker does not hold.
- **Purpose**: App functionality.
- **Optional**: Yes — users can use the app fully without it.
- **Deletion**: Settings → This phone → delete all data; and per-group and per-person history
  deletion from the map.

## Reviewer instructions

Paste into the "instructions for reviewers" box:

```
The map needs a second member to show anything, and joining requires an invite from an existing
member, so please use the demonstration video rather than trying to reproduce it from a fresh
install.

To reach the feature: create a group, open it, choose the Map tab, tap the gear, and turn on
"Location sharing for this group" (admin) and then "Share my location" (your own switch). The
runtime location prompt appears when the second switch is touched, and not before.
```

## Demonstration video

Not required. The video field on this form exists to verify a background-location claim, and
this app makes none. If Play asks anyway, the answer is that the app declares only foreground
location permissions; point at the manifest.
