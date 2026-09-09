# Google Drive storage setup for Chit Chat

The application now supports Google Drive as the file-storage backend. The browser uploads file bytes directly to Google Drive using a resumable upload session created by Spring Boot. Render only handles authentication, upload-session creation, upload completion/validation, and chat metadata.

## 1. Create a Google Cloud project

Create or select a project in Google Cloud Console and enable the **Google Drive API**.

## 2. Configure OAuth consent

Configure the OAuth consent screen for the Google account that owns the Chit Chat storage folder.

The backend uses this Drive scope:

`https://www.googleapis.com/auth/drive`

For initial testing, the OAuth app can remain in Testing mode and the Google account used for the refresh token must be added as a test user. Google may expire refresh tokens for apps left in Testing mode; for long-term use, review Google's current OAuth verification/publishing requirements.

## 3. Create OAuth credentials

Create an OAuth 2.0 client ID. A Desktop application client is convenient for obtaining a refresh token with OAuth Playground.

Use Google OAuth Playground with your own OAuth credentials and request:

`https://www.googleapis.com/auth/drive`

Authorize the Google account that owns the storage folder, exchange the authorization code for tokens, and copy the **refresh token**.

Never put the client secret or refresh token in GitHub or in frontend code.

## 4. Create the Drive folder

Create a folder in the same Google Drive account. Copy the folder ID from the URL:

`https://drive.google.com/drive/folders/FOLDER_ID`

The value after `/folders/` is `GOOGLE_DRIVE_FOLDER_ID`.

## 5. Add Render environment variables

In the Render backend service, add:

- `GOOGLE_DRIVE_CLIENT_ID`
- `GOOGLE_DRIVE_CLIENT_SECRET`
- `GOOGLE_DRIVE_REFRESH_TOKEN`
- `GOOGLE_DRIVE_FOLDER_ID`
- `GOOGLE_DRIVE_PUBLIC_FILES=true`

The first four values must stay private.

## 6. Frontend configuration

The frontend currently defaults to Drive storage:

`VITE_FILE_STORAGE=drive`

No frontend secret is required.

For an emergency rollback during testing, set:

`VITE_FILE_STORAGE=cloudinary`

This keeps the old Cloudinary upload endpoint available until the Drive path is proven in the Nirma lab.

## 7. What the upload path does

1. React asks Spring Boot for a Drive resumable upload session.
2. Spring Boot authenticates with Google and returns the session URL.
3. React uploads the selected file directly to Google Drive with `PUT` and reports progress locally.
4. React sends the returned Drive file ID to Spring Boot.
5. Spring Boot verifies name, MIME type, size, folder, and trash state.
6. When `GOOGLE_DRIVE_PUBLIC_FILES=true`, Spring Boot gives the file an `anyone:reader` permission so other chat users can open the direct Drive link without signing into the storage account.
7. MongoDB stores the Drive file ID and direct URL as chat-file metadata.

Large file bytes therefore do not pass through Render.

## 8. Before deleting Cloudinary

Do not remove the Cloudinary fallback until the following have been tested in the Nirma lab:

- login/chat still works
- 20–25 MB upload succeeds
- upload progress reaches 100%
- another browser/account can open an image
- another browser/account can download a PDF/ZIP/etc.
- video/audio files play or download as expected
- refresh/reload still shows the uploaded file
- a deleted message no longer exposes the file through the chat UI

Only after those checks pass should the old Cloudinary upload/proxy path be removed.
