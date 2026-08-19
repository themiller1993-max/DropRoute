# DropRoute

DropRoute is an Android delivery-route planner prototype focused on multi-stop route planning, delivery-day logging, in-app navigation and optional live-traffic routing.

## First-run setup

On first launch, DropRoute asks for:

- usual start location
- fuel / end-of-route stop
- final destination / home
- usual start time
- default minutes per delivery
- optional Mapbox public access token for traffic-aware routing and ETA

These settings are stored on the device and can be changed later in Settings.

## Privacy

The public build contains no user-specific locations, route history, mileage, Mapbox token, or delivery records. Runtime data is stored locally on the device.

## Build

The current prototype uses a small generated Android WebView shell and a self-contained HTML/JavaScript app. Run `python build_apk.py` to create the unsigned APK. A release APK should be signed with your own Android signing key.

## Status

Beta/prototype. Test carefully before relying on it for operational delivery work.
