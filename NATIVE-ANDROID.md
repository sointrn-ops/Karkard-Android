# KARBAN Native Android

This branch contains the native Android implementation built with Kotlin and Jetpack Compose.

## Data continuity

The app keeps the existing application ID, private file name, encrypted storage prefix, and Android Keystore alias used by the WebView release:
- Private file: `karban_records.json`
- Encrypted prefix: `KARBAN1:`
- Keystore alias: `karban_records_key_v1`
- Cipher: AES-256-GCM

The JSON record fields remain compatible with the previous app: `id`, `date`, `advances`, `overtimes`, and `report`.

## Backup before upgrading

Export a JSON backup from the currently installed app before installing a build signed with a different key. Android may reject an in-place update if the APK signing certificate differs. Uninstalling the old app removes its private app data, so keep the backup in a safe location and restore it after installing the native version.

Backup JSON files are not encrypted. Keep them private.

## Build

GitHub Actions builds the debug APK for pushes to this branch and for pull requests targeting `main`.
