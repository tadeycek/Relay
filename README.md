# Relay

Relay is an Android SMS/MMS messenger with map-based location sharing.

It is designed for direct phone-to-phone communication using carrier SMS/MMS, with Relay-specific enhancements for location pins, trust controls, and lightweight group workflows.

## Features

- SMS chat for contacts and groups
- MMS support for images and short videos
- Map-first UI for dropping and sending location pins
- Pin metadata: label + expiry
- Pin history screen
- Contact trust levels (`Trusted`, `Ask`, `Blocked`)
- Do Not Disturb window for location requests
- Read receipt protocol for Relay messages

## Tech Stack

- Kotlin
- Jetpack Compose (Material 3)
- Android SDK 26+ (target/compile 36)
- SQLite (`SQLiteOpenHelper`, no Room)
- Coroutines
- OSMDroid map rendering

## Project Structure

`app/src/main/java/com/relay/app`

- `ui/` screens, components, theme, navigation
- `data/` models, repositories, SQLite contract/helper
- `sms/` SMS receiver/sender and location request handling
- `mms/` MMS sender/receiver + media prep
- `util/` preferences and SMS protocol parsing

## Build and Run

### Prerequisites

- Android Studio (recent version)
- Android SDK + platform tools
- JDK 17+
- A physical Android device is strongly recommended for SMS/MMS testing

### Steps

1. Clone the repo.
2. Open it in Android Studio.
3. Let Gradle sync.
4. Run:
   - Debug build from IDE, or
   - `./gradlew assembleDebug`

The debug APK is generated at:

- `app/build/outputs/apk/debug/app-debug.apk`

## Required Permissions

Relay uses SMS/MMS/location/media permissions, including:

- `SEND_SMS`, `RECEIVE_SMS`, `READ_SMS`
- `RECEIVE_MMS`, `READ_MMS`
- `ACCESS_FINE_LOCATION`
- `CAMERA`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`
- `INTERNET`, `ACCESS_NETWORK_STATE`

For full behavior, Relay should be set as the device's default SMS app.

## SMS Protocol Notes

Relay encodes structured messages using a `TYPE:...` format, for example:

- `TYPE:LOCATION|LAT:...|LNG:...|EXPIRY:...|LABEL:...`
- `TYPE:LOCATION_REQUEST`
- `TYPE:READ_RECEIPT|MSG_ID:...`

Non-Relay messages (bank OTP, carrier texts, etc.) are treated as normal SMS content.

## Sharing the App with Friends

Quickest method for testing:

1. Build `app-debug.apk`.
2. Send the APK file directly (AirDrop, Drive, Telegram, etc.).
3. Your friend enables "Install unknown apps" for the installer app.
4. They install and open Relay.
5. Grant required permissions and set Relay as default SMS app.

For broader distribution, use a signed release APK/AAB via Google Play internal testing.

## Current Limitations

- MMS behavior can vary by carrier/device.
- Running with compileSdk 36 on AGP 8.3.2 may show compatibility warnings.
- Some advanced Android behaviors (background constraints/OEM battery policies) vary by vendor.

## License

Add your preferred license (MIT/Apache-2.0/etc.) here before public release.
