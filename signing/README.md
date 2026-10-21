# Release signing

- `cleartv-release.jks`: PKCS12 keystore, alias `cleartv`, RSA 3072, valid 10000 days, generated with keytool.
- `signing.properties`: store path, alias and the random password. Both files are git-ignored.

Keep both files. Android only installs an update over an existing install when it is signed with the same key,
so losing the keystore means uninstalling the app (and its bookmarks/settings) before installing a new build.
