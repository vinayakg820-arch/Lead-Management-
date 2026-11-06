# LeadMaster – Lead Management (Web + Android APK)

Supabase project: **Lead Management** (`cssswtyzfmfoijcjgxpe`). The web app and the APK use the same data.

```
web/index.html               → the web app (upload to EdgeOne)
android/                     → Android app project (WebView + native WhatsApp photo sharing)
.github/workflows/build-apk.yml → GitHub builds the APK automatically
supabase/product_catalogue.sql  → database changes for the catalogue (already applied)
```

---

## 1. Web app (EdgeOne)

Upload `web/index.html` to your EdgeOne Pages project (replace the old file), then press Ctrl+Shift+R.

## 2. APK – GitHub वर build करणे

1. (एकदाच) Repository → **Settings → Secrets and variables → Actions → New repository secret**
   - Name: `KEYSTORE_BASE64`
   - Value: `KEYSTORE_BASE64.txt` मधील पूर्ण मजकूर (ही file GitHub वर upload करू नका).
   यामुळे प्रत्येक नवीन APK जुन्या app वर update म्हणून install होईल.
2. **Actions** tab → "Build Android APK" → **Run workflow** (किंवा कोणताही commit). सुमारे 5–8 मिनिटे.
3. **Releases** मध्ये प्रत्येक version साठी दोन files मिळतात:
   - `LeadMaster-v1.0.X.apk` → Android phone वर install (जुन्या app वर update होतो)
   - `LeadMaster-EdgeOne-v1.0.X.zip` → EdgeOne Pages वर upload (New Deployment)
   App च्या login page आणि menu च्या खाली **Version 1.0.X** दिसतो, त्यावरून कोणता version चालू आहे ते कळते.

प्रत्येक वेळी तुम्ही `web/index.html` बदलून commit कराल, तेव्हा नवीन APK आपोआप तयार होईल.

## 3. APK install करणे

1. APK phone वर download करा आणि open करा.
2. "Install unknown apps" विचारल्यास Chrome / Files ला **Allow** करा.
3. Install करा. नवीन APK जुन्या app वर update म्हणून install होतो (data Supabase मध्ये असल्याने काही जात नाही).

## 4. Product Catalogue आणि WhatsApp

- **Settings → Product Catalogue → Add Product**: name, description, price, photos (6 पर्यंत).
- "Send this product in the WhatsApp message" tick असलेले products message मध्ये जातात.
- Lead वरचे WhatsApp button:
  - **Android APK**: welcome message + सर्व product photos थेट customer च्या chat मध्ये जातात (एकूण 30 photos पर्यंत). फक्त Send दाबा.
  - **Browser (computer / mobile browser)**: browser photos attach करू शकत नाही, म्हणून message मध्ये प्रत्येक product चा photo link जातो.
- Settings मध्ये "WhatsApp app in the Android APK" निवडा: Automatic / WhatsApp Business / WhatsApp.

> टीप: थेट customer चा chat उघडणे WhatsApp च्या undocumented `jid` पद्धतीवर अवलंबून आहे. एखाद्या WhatsApp version मध्ये ते चालले नाही तर WhatsApp contact निवडायला सांगेल.

## Security notes

- The APK signing key is NOT in this repository. It is stored as the `KEYSTORE_BASE64` GitHub secret. Never commit a `.jks` file here (the repository is public).
- The Supabase key in `index.html` is the public anon key; data is protected by Row Level Security.

## 5. In-app updates (v1.0.5 पासून)

- GitHub वर नवीन version तयार झाला की app मध्ये **"New Update Available"** pop-up येतो → **Update Now** → update होऊन **Congratulations** message येतो.
- **Android app (v1.0.11 पासून):** app तुमची EdgeOne website तपासतो. नवीन `LeadMaster-EdgeOne-v….zip` EdgeOne वर upload केली की app मध्ये "Update Now" येतो आणि काही सेकंदात update होतो (APK install नाही). Website address Settings मध्ये बदलता येतो.
- Java/Manifest बदलला असेल तर `MainActivity.java` मधील `NATIVE_VERSION` आणि `web/index.html` मधील `MIN_NATIVE_VERSION` दोन्ही 1 ने वाढवा; मग app नवीन APK download करून Install विचारतो.
- **Browser (EdgeOne):** नवीन `LeadMaster-EdgeOne-v….zip` EdgeOne वर deploy केल्यानंतरच browser users ना pop-up दिसतो.
- Commit message चा मजकूर "What's new" मध्ये दिसतो.

## 6. Lead notifications

- Admin ने lead assign/edit केला की त्या user ला pop notification (आवाजासह) येते; user ने lead बदलला/जोडला की admin ला येते.
- App background मध्ये असताना Android notification / browser notification येते (पहिल्यांदा permission Allow करा).
- App पूर्ण बंद असताना notification येत नाही (त्यासाठी Firebase push लागेल).
