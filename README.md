# Android TV File Picker

Android TV File Picker is an app for Android TV. It is a file picker and a folder picker for other apps.

## Purpose

Many Android TV devices do not have a system file picker. Apps that use the Storage Access Framework (SAF) cannot open or save files on these devices. The app shows an error or does nothing.

This app solves the problem. It registers as an SAF picker and as a document provider. Other apps can then open files, save files, and select folders.

## Requirements

| Item | Value |
|------|-------|
| Device | Android TV 11 or newer (API level 30 or higher) |
| Device permission | All files access (`MANAGE_EXTERNAL_STORAGE`) |
| Build tools | JDK 17, Android SDK 34, Gradle 8.9 |

## Supported intents

The picker handles these intents.

| Intent action | Result |
|---------------|--------|
| `ACTION_OPEN_DOCUMENT` | One file, or many files if the caller allows it |
| `ACTION_GET_CONTENT` | One file, or many files if the caller allows it |
| `ACTION_CREATE_DOCUMENT` | A new empty file in the folder that the user selects |
| `ACTION_OPEN_DOCUMENT_TREE` | A folder, with access to all files inside it |

The picker supports these extras: `EXTRA_ALLOW_MULTIPLE`, `EXTRA_MIME_TYPES`, and `EXTRA_TITLE`. It also supports MIME type filters in the intent type.

## How the app works

1. The app finds each mounted storage volume with `StorageManager`. This includes USB drives.
2. The picker shows the folders and files on a screen that the remote control can operate.
3. The user selects an item. The picker returns a `content://` URI to the calling app. The URI has a permission grant.
4. The document provider (`StorageDocumentsProvider`) supplies the file data to the calling app.

The document ID of each file is its absolute path. The provider rejects each path that is outside a storage volume.

## Install the app

You need the APK file. Refer to [Build the app](#build-the-app) or [Build with GitHub Actions](#build-with-github-actions).

1. On the TV, turn on developer options and network debugging.
2. On your computer, connect to the TV:

   ```
   adb connect <TV-IP-ADDRESS>:5555
   ```

3. Install the APK:

   ```
   adb install Android-TV-File-Picker.apk
   ```

## Give storage access to the app

The picker cannot read files until you give it All files access.

1. Start **Android TV File Picker** from the home screen of the TV.
2. Select **Open settings**.
3. Turn on the switch for the app.
4. Press Back until the start screen shows `All files access: on`.

If the settings screen does not open, use ADB:

```
adb shell appops set com.example.androidtvfilepicker MANAGE_EXTERNAL_STORAGE allow
```

## Use the picker

1. In another app, start an action that opens a file, saves a file, or selects a folder.
2. If Android shows a list of apps, select **Android TV File Picker**.
3. Use the controls in the table below.

| Control | Action |
|---------|--------|
| Up, Down | Move in the list |
| Select (OK) | Open a folder, or select a file |
| Back | Go up one folder. At the top level, cancel the picker |
| Left | Move to the buttons on the left side |

In each mode, the buttons on the left side do these actions:

| Mode | Button | Action |
|------|--------|--------|
| Select many files | **Select (n)** | Return the selected files |
| Select a folder | **Use this folder** | Return the folder that is open |
| Save a file | **Save here** | Create the file with the name in the text field |

If a file with the same name exists, the picker adds a number to the new name. For example, `report (1).pdf`.

## Test the picker

The start screen has two test buttons: **Test: pick a file** and **Test: pick a folder**. Each button starts the picker and shows the URI that the picker returns.

## Build the app

This repository does not include the Gradle wrapper files.

### Build with Android Studio

1. Open the project folder in Android Studio.
2. Wait for the Gradle sync to end.
3. Select **Build > Generate Signed App Bundle / APK**, or run the `assembleRelease` task.

### Build with the command line

1. Install JDK 17, Android SDK 34, and Gradle 8.9.
2. Set the `ANDROID_HOME` variable to the path of the Android SDK.
3. Run:

   ```
   gradle :app:assembleRelease
   ```

4. Get the APK from `app/build/outputs/apk/release/app-release.apk`.

If you do not set a release key, the build signs the APK with the debug key.

## Build with GitHub Actions

The workflow `.github/workflows/build-release.yml` builds the release APK on demand. It does not run automatically.

1. Push the project to GitHub.
2. Open the **Actions** tab of the repository.
3. In the list on the left, select **Build release APK**.
4. Select **Run workflow**.
5. Set the inputs, if necessary.
6. Select the green **Run workflow** button.
7. When the job ends, open the run summary.
8. Download the artifact `Android-TV-File-Picker-apk`.

### Workflow inputs

| Input | Description |
|-------|-------------|
| `version_name` | Version name of the APK, for example `1.0.1`. If empty, the build uses the default in `app/build.gradle.kts`. |
| `publish_release` | If on, the workflow publishes the APK as a GitHub release. |
| `release_tag` | Tag of the release, for example `v1.0.1`. If empty, the workflow uses `v` and the version name. |

The version code is the run number of the workflow.

### Sign the APK

To sign the APK with your own key, add these secrets to the repository. Open **Settings > Secrets and variables > Actions**.

| Secret | Value |
|--------|-------|
| `KEYSTORE_BASE64` | The key store file, encoded in Base64 |
| `KEYSTORE_PASSWORD` | The password of the key store |
| `KEY_ALIAS` | The alias of the key |
| `KEY_PASSWORD` | The password of the key |

To create a key store and encode it:

```
keytool -genkeypair -v -keystore release.jks -alias release \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w 0 release.jks
```

> **CAUTION**
> Do not add the key store file to the repository. Keep a backup of the key store. If you lose the key, you cannot update an installed app.

> **NOTE**
> If the secrets are not set, the workflow signs the APK with the debug key. Do not give a debug-signed APK to the public.

## Change the package name

The default package name is `com.example.androidtvfilepicker`. To change it:

1. Change `namespace` and `applicationId` in `app/build.gradle.kts`.
2. Rename the package of the Kotlin files in `app/src/main/java`.
3. Do the ADB commands again with the new package name.

## Project structure

```
.
├── .github/workflows/build-release.yml   Workflow for the release APK
├── app/
│   ├── build.gradle.kts                  App build configuration
│   └── src/main/
│       ├── AndroidManifest.xml           Intent filters and provider
│       ├── java/.../MainActivity.kt      Start screen and test buttons
│       ├── java/.../PickerActivity.kt    The picker screen
│       ├── java/.../FileAdapter.kt       List of storage, folders, and files
│       ├── java/.../Storage.kt           Volumes, paths, MIME types, permission
│       ├── java/.../StorageDocumentsProvider.kt   The document provider
│       └── res/                          Layouts, strings, icons
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## Limits

- Android 11 and newer blocks access to the `Android/data` and `Android/obb` folders. The picker cannot open these folders, also with All files access.
- The app works with local storage only. It does not support cloud storage or network shares.
- The banner on the home screen is a plain image. Replace `res/drawable/banner.xml` with your own banner (320 x 180 pixels).

## Troubleshooting

| Problem | Cause | Solution |
|---------|-------|----------|
| The picker shows `All files access is off` | The permission is not given | Give storage access to the app. Refer to [Give storage access to the app](#give-storage-access-to-the-app). |
| The settings screen does not open | The TV does not have this screen | Use the ADB command. |
| The picker shows `The app cannot read this folder` | The folder is protected by Android | Select a different folder. |
| A USB drive is not in the list | The drive is not mounted | Disconnect the drive and connect it again. Then start the picker again. |
| The other app does not show the picker | The app does not use SAF | Use an app that supports SAF. |
| The APK does not install | An app with the same package name and a different key is installed | Uninstall the app. Then install the APK again. |

## Document style

This README follows ASD-STE100 Simplified Technical English.
