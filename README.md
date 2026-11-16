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
| Root access (optional) | A rooted device. This is necessary only for [root support](#root-support-optional). |
| Network (optional) | An SMB 2/3 server or a WebDAV server. This is necessary only for [network shares](#network-shares-smb-and-webdav). |
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

The grants that the picker returns are the same as the grants of the system picker. For `ACTION_OPEN_DOCUMENT`, `ACTION_CREATE_DOCUMENT`, and `ACTION_OPEN_DOCUMENT_TREE`, the grant is persistable and has read and write access. For `ACTION_OPEN_DOCUMENT_TREE`, the grant is also valid for all items in the folder. For `ACTION_GET_CONTENT`, the grant is temporary and has read access.

## How the app works

1. The app finds each mounted storage volume with `StorageManager`. This includes USB drives.
2. The picker shows the folders and files on a screen that the remote control can operate.
3. The user selects an item. The picker returns a `content://` URI to the calling app. The URI has a permission grant.
4. The document provider (`StorageDocumentsProvider`) supplies the file data to the calling app.
5. If you turned on [root support](#root-support-optional), the picker also opens protected folders with root.
6. If you added [network shares](#network-shares-smb-and-webdav), the picker also shows them. The provider reads and writes the files on the server.

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

## Root support (optional)

Android blocks access to the `Android/data` and `Android/obb` folders. This is also the case with All files access. Root support lets the picker open these protected folders on a rooted device.

**Root support is off by default.** The picker does not use root until you turn it on.

### Requirements for root support

- A rooted device. The `su` command must be available. For example, Magisk or KernelSU.
- Root permission for the app. The root manager asks you for this permission.

### Turn root support on

1. Start **Android TV File Picker** from the home screen of the TV.
2. Select **Root support: off**.
3. Read the warning. Select **Turn on**.
4. If the root manager shows a request, allow it.

When root access works, the button shows **Root support: on**. To turn root support off, select the button again.

You can also turn root support on from the picker. Open a protected folder. The picker shows the same warning.

### Consent for each share

Each time you select a file or folder in a protected location, the picker shows a message. The message gives the name of the calling app. Select **Allow** to share the item. Select **Cancel** to stop. The focus is on **Cancel** at the start.

### How root support works

- The picker uses `su` to list, read, and write files in `Android/data` and `Android/obb`.
- The picker uses the storage paths that Android does not filter. These are `/data/media/<user>` for internal storage and `/mnt/media_rw/<id>` for removable storage.
- The picker examines the real path of each item before it uses the item. It rejects each path that leaves the protected folders. This includes paths through symbolic links.
- A protected item has a document ID with the prefix `root:`. A grant for a normal folder never includes protected items. For example, a grant for a complete storage volume does not include `Android/data`.
- A normal folder listing that another app requests does not show `Android/data` and `Android/obb`.
- The picker supplies new files with the owner, group, and SELinux label of the parent folder.
- A calling app that has access can rename an item in a protected folder. A rename does not delete data.

### Limits of root support

- A read can use random access. A read can be slower than a normal read, because the picker uses a `su` process.
- A write must be in sequence. The modes `w`, `wt`, and `wa` are supported. The mode `rw` is not supported.
- The picker cannot delete items in protected folders.
- A rename keeps the item in its folder. A rename fails if an item with the new name exists. The picker does not rename `Android/data` and `Android/obb` themselves.
- Each action starts a `su` process. A large folder can be slow to open.
- On some devices, SELinux can deny access to a new file for the app that owns the folder. Do a test with your apps.

> **WARNING**
> Root access gives the app full control of the device. If you share a file from a protected folder, the calling app can read private data of other apps. Turn on root support only if you trust the apps that you use with the picker.

## Network shares (SMB and WebDAV)

The picker can open files on a network share. A calling app then gets access to the movies, music, game ROMs, and other files on a NAS or a computer.

### Supported protocols

| Protocol | Notes |
|----------|-------|
| SMB | SMB 2 and SMB 3. SMB 1 is not supported. Sign-in with user name and password, or as a guest. |
| WebDAV | HTTP and HTTPS. Basic authentication only. The server must support HTTP range requests. |

### Add a share

1. Start **Android TV File Picker** from the home screen of the TV.
2. Select **Network shares**.
3. Select **Add SMB share** or **Add WebDAV share**.
4. Enter the values from the table below.
5. Select **Test**. The app shows if the connection works.
6. Select **Save**.

| Field | Description |
|-------|-------------|
| Name | The name that the picker shows. If empty, the picker shows the address. |
| Server (SMB) | The name or the IP address of the server. For a port that is not 445, use `host:port`. |
| URL (WebDAV) | The URL of the WebDAV folder, for example `https://nas.local/dav`. |
| Share name (SMB) | The name of the share on the server. |
| Folder (SMB) | A folder in the share, for example `Media/Movies`. This is optional. |
| User name | The user name. If empty, the app uses a guest login. |
| Password | The password. If you change a share, an empty field keeps the old password. |
| Domain (SMB) | The domain of the user. This is optional. |
| Read-only | If on, the calling apps cannot write, create, delete, or rename. |
| Trust all certificates (WebDAV) | If on, the app accepts each TLS certificate. This is not safe. |

To change or delete a share, select it in the list.

When you select **Test**, the app keeps the values of the form until you close the form. If the app closes during a test, the next form for the same share shows the values again.

### Use a share

The picker shows each share after the storage volumes in the top-level list. Select the share. Then select a folder or a file. The calling app gets access to the item, and not to the rest of the share.

### Safety of network shares

- A new share is **read-only**. Turn this off only if an app must write to the share.
- The app encrypts each password with a key in the Android Keystore. The app does not include the passwords in a backup.
- A document ID for a share has the prefix `net:` and the ID of the share. A grant for one share never includes another share, local storage, or a protected folder.
- The app rejects each path with `..` or a backslash.
- HTTP sends a Basic password without encryption. Use HTTPS if possible. The editor shows a warning for an HTTP URL.
- Use **Trust all certificates** only in a network that you trust. The option applies to one share.

### Limits of network shares

- A read can use random access. The picker reads blocks of 1 MiB. A slow network limits the speed of large files.
- An SMB share supports the modes `r`, `w`, `wt`, `wa`, `rw`, and `rwt`. A WebDAV share supports `r`, `w`, and `wt`. A WebDAV write must be in sequence.
- A WebDAV write is one HTTP request. If the request fails, the calling app can get the error after it closes the file, or it cannot get the error.
- Apps that need a file path, for example some emulators, cannot use a network share. They must support SAF or file descriptors.
- WebDAV supports only Basic authentication. A server that needs Digest authentication does not work.
- The app does not find servers automatically. It does not support NFS, FTP, or SFTP.
- The app keeps the connection of a share open. If the server closes the connection, the app connects again.

## Error report

If an error stops the app, the app saves the text of the error. The start screen shows the first lines of the text. Select **Delete the error report** to remove it.

The app does not send the report to a network. The text can include names of servers, folders, and files. Read the text before you share it.

If the app returns to the start screen and there is no error report, Android stopped the app. For example, the device had no free memory.

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

## Shrink the code (R8)

The release build uses R8 to remove unused code and unused resources. The libraries for the network shares (smbj and BouncyCastle) load some classes by name. R8 cannot see these uses. The rules in `app/proguard-rules.pro` keep these classes.

A problem with R8 does not show at build time. It shows when the app uses the feature. After each change to the libraries or to the rules, do these tests with the release APK on a device:

1. Open an SMB share. Open a folder, read a file, and write a file.
2. Open a WebDAV share. Open a folder and read a file.
3. If you use root support, open a protected folder.

### If the build fails with "Missing classes"

1. Get the file `missing_rules.txt`. In GitHub Actions, the workflow uploads it as the artifact `Android-TV-File-Picker-r8-suggestions` if the job fails. On your computer, the file is in `app/build/outputs/mapping/release/`.
2. Copy the lines from the file into `app/proguard-rules.pro`.
3. Build again.

### If the release APK crashes, and the debug APK does not

1. Get the stack trace with `adb logcat`.
2. Decode the stack trace with the file `mapping.txt` and the `retrace` tool. The workflow uploads the file as the artifact `Android-TV-File-Picker-mapping`.
3. Add a `-keep` rule for the class that the stack trace names.
4. Build again and do the tests.

To find out if R8 causes a problem, set `isMinifyEnabled` and `isShrinkResources` to `false` in `app/build.gradle.kts`. Then build again.

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

The run also has the artifact `Android-TV-File-Picker-mapping`. Keep this file for each APK that you give to other people. You need it to read crash reports. Refer to [Shrink the code (R8)](#shrink-the-code-r8).

### Workflow inputs

| Input | Description |
|-------|-------------|
| `version_name` | Version name of the APK, for example `1.0.1`. If empty, the build uses the default in `app/build.gradle.kts`. |
| `publish_release` | If on, the workflow publishes the APK as a GitHub release. |
| `release_tag` | Tag of the release, for example `v1.0.1`. If empty, the workflow uses `v` and the version name. |

The workflow builds the branch that you select in the **Run workflow** menu. A new tag points to the commit that the workflow built, and the release notes show the commits of that branch. If the tag exists and points to a different commit, the workflow stops. Use a new tag, or delete the old tag.

If you build a branch that is not the default branch, the workflow marks the release as a pre-release. A build of the default branch is a normal release.

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
│   ├── proguard-rules.pro                R8 rules for the libraries
│   └── src/main/
│       ├── AndroidManifest.xml           Intent filters and provider
│       ├── java/.../MainActivity.kt      Start screen and test buttons
│       ├── java/.../PickerActivity.kt    The picker screen
│       ├── java/.../FileAdapter.kt       List of storage, folders, and files
│       ├── java/.../Storage.kt           Volumes, paths, MIME types, permission
│       ├── java/.../RootAccess.kt        Root setting, path rules, list, create, open
│       ├── java/.../RootShell.kt         Runs commands with su
│       ├── java/.../RootConsent.kt       Consent dialogs for root
│       ├── java/.../NetAccess.kt         Network shares: IDs, paths, open, create
│       ├── java/.../RemoteClient.kt      Interface of a client, and the client cache
│       ├── java/.../SmbRemoteClient.kt   SMB client (smbj)
│       ├── java/.../WebDavRemoteClient.kt   WebDAV client (OkHttp)
│       ├── java/.../Shares.kt            The list of shares
│       ├── java/.../Secret.kt            Password encryption (Android Keystore)
│       ├── java/.../SharesActivity.kt    The screen for the shares
│       ├── java/.../App.kt               The application class. It starts the error report.
│       ├── java/.../CrashLog.kt          Saves the text of the last error
│       ├── java/.../StorageDocumentsProvider.kt   The document provider
│       └── res/                          Layouts, strings, icons
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## Limits

- Android 11 and newer blocks access to the `Android/data` and `Android/obb` folders. The picker cannot open these folders, also with All files access. On a rooted device, you can turn on [root support](#root-support-optional).
- The app supports local storage, SMB shares, and WebDAV shares. It does not support cloud storage, NFS, FTP, or SFTP.
- The release build shrinks the code with R8. If a feature fails only in the release APK, refer to [Shrink the code (R8)](#shrink-the-code-r8).
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
| The test of an SMB share shows `STATUS_LOGON_FAILURE` | The user name, password, or domain is not correct | Correct the values. Then do the test again. |
| The test of an SMB share shows a connection error | The server is off, the address is not correct, or a firewall blocks port 445 | Examine the address and the network. The server must support SMB 2 or SMB 3. |
| The test of a WebDAV share shows `Access denied (HTTP 401)` | The password is not correct, or the server needs Digest authentication | Correct the password. Change the server to Basic authentication over HTTPS. |
| The test of a WebDAV share shows a certificate error | The server uses a certificate that Android does not trust | Install a trusted certificate on the server. Or turn on **Trust all certificates** for a network that you trust. |
| The test shows a library error, for example `NoClassDefFoundError` | R8 removed a class that the library needs, or the library does not work on this device | Refer to [Shrink the code (R8)](#shrink-the-code-r8). Add a keep rule for the class in the message. To find out if R8 is the cause, turn R8 off. |
| The app returns to the start screen when you use the form of a share | An error stopped the app, or Android stopped the app | Look at the error report on the start screen. If there is no report, Android stopped the app. |
| A calling app cannot play or open a file from a share | The app needs a file path, or the WebDAV server does not support range requests | Use an app that supports SAF. Turn on range requests on the server. |
| The picker shows `Android protects this folder` | Root support is off | Turn on [root support](#root-support-optional). |
| Root support does not turn on | The device has no root, or the root manager denied the app | Give root permission to the app in the root manager. Then try again. |
| A protected file opens, but the owner app cannot read a new file | The owner or the SELinux label of the new file is not correct | Do a test with the owner app. Use the owner app to create the file, if possible. |

## Document style

This README follows ASD-STE100 Simplified Technical English.
