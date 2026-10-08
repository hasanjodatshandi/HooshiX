# فعال‌سازی OpenBao نصب‌شده

## محدوده

نصب واقعی روی VPS در ۲۰۲۶-۱۰-۰۸ با receipt عمومی
`hooshix-platform-d6c52f209c034d019f67ee38b727724a.json` موفق شد:
OpenBao 2.6.4 نصب شده، sealed و هنوز initialize نشده است. Root، SSH، ایمیل و
storage دوباره ساخته یا نصب نمی‌شوند. Stage 10 و Production readiness هنوز
`Not verified` هستند.

هدف این تغییر واحد، initialization با Shamir سه سهم/آستانهٔ دو، رمزکردن سهم‌ها
قبل از خروج از OpenBao، تحویل و آزمایش custody بیرون VPS، و unseal است.
این فقط bootstrap محدود ADR-0030 است، نه جایگزین GitOps/JIT؛ client ingress،
port عمومی، Kubernetes Auth و policy برنامه تغییر نمی‌کنند. API audit باید
Metadata باشد؛ عملیات CLI با CA mount‌شده TLS را بررسی می‌کند و هیچ secret در
argv/environment/فایل plaintext/خروجی قرار نمی‌گیرد.

## اجرای مالک

پس از merge و CI موفق، با VNC کارا و نشست دوم خصوصی SSH در PowerShell محلی،
بدون transcript/ضبط صفحه اجرا کنید:

```powershell
wsl.exe -d Ubuntu --cd /home/coder/workspace/Hooshix-platform-commissioning python3 scripts/production/activate_openbao_operator.py --rescue-and-second-session-ready
```

checkout باید تمیز و revision عضو `origin/main` باشد. ابزار از SSH/SCP موجود
Windows و alias موجود `hooshix-server` استفاده می‌کند؛ canonical checkout
دارای تغییرهای دیگر، forwarding و SSH را عوض نمی‌کند.

1. با برقرار بودن مسیر نجات، `READY` بنویسید.
2. یک رمز **تازهٔ custody OpenBao**، ۲۰ تا ۱۲۸ نویسه، وارد و تکرار کنید و در
   password manager نگه دارید. این رمز Root/Intermediate/GHCR/sudo نیست.
3. سه کلید RSA-3072 OpenPGP روی WSL دستگاه خودتان ساخته می‌شوند. exportهای
   خصوصی با همان رمز محافظت می‌شوند. قبل از init، recovery از export در keyring
   تازه و رد رمز اشتباه آزمایش می‌شود. GnuPG موجود 2.4.8 روی WSL فعلی و 2.4.4
   در runner پشتیبانی می‌شوند؛ چیزی نصب یا به keyring شخصی import نمی‌شود.
4. رمز sudo را فقط در prompt مخفی محلی وارد کنید. supervisor hash منبع را پیش
   از اجرای root بررسی می‌کند؛ هر عملیات هدف deadline سه‌دقیقه‌ای دارد.
5. خود OpenBao سهم‌ها و root token اولیه را با public keyها رمز می‌کند؛ فقط
   ciphertext به دستگاه شما برمی‌گردد. private key و رمز custody به VPS نمی‌روند.
6. مسیر `CUSTODY_DIRECTORY` بیرون پروژه چاپ می‌شود. پس از readback دیسک، هر سه
   سهم و token از backup خصوصی در حافظه باز می‌شوند؛ plaintext نمایش/ذخیره
   نمی‌شود. فقط پنج فایل زیر برای custody لازم‌اند: `recipients.json`،
   `encrypted.json` و سه `recipient-N.secret.pgp`. در Explorer، `ID` را با
   شناسهٔ چاپ‌شده جایگزین کنید:

   ```text
   \\wsl.localhost\Ubuntu\home\coder\.local\share\hooshix-openbao-custody\ID
   ```

7. نسخه‌های رمزدار را در دو محل custody مصوب خودتان کپی و وجودشان را بررسی
   کنید؛ سپس در همان پنجره `COPIED` بنویسید. این تأیید مالک است، نه اثبات مستقل
   توزیع. سه private key با یک رمز مالک محافظت می‌شوند؛ ادعای custody چندنفره
   یا سخت‌افزاری نداریم. برای محافظت از quorum، حداقل دو سهم و کلیدهای متناظرشان
   را در دسترس عادی یک دستگاه نگذارید.
8. فقط بعد از recovery و تأیید custody، دو سهم حافظه‌ای با SSH رمزدار و JSON
   stdin وارد OpenBao می‌شوند. سهم اول باید sealed بماند؛ سهم دوم unseal می‌کند.

موفقیت فقط با `OPENBAO_ACTIVATION=Passed` و `activation-receipt-*.json` ثبت
می‌شود. فقط receipt عمومی را به دستیار بدهید؛ exportها، `encrypted.json`، رمز،
سهم یا token را در چت/Git نفرستید.

## قطع اجرا، ادامه و unseal پس از restart

ابزار کلید تازه یا Root تازه نمی‌سازد. ciphertext و intent روی VPS هم در مسیر
root-only `/var/lib/hooshix-pki/openbao-activation` می‌مانند؛ این backup بیرون
میزبان نیست. پوشهٔ custody محلی را تا recovery/cutover پاک نکنید. پس از قطع
دانلود یا قبل از unseal با همان `ID` ادامه دهید:

```powershell
wsl.exe -d Ubuntu --cd /home/coder/workspace/Hooshix-platform-commissioning python3 scripts/production/activate_openbao_operator.py --rescue-and-second-session-ready --resume /home/coder/.local/share/hooshix-openbao-custody/ID
```

با همان recipients/PVC، init تکرار نمی‌شود و ciphertext قبلی برمی‌گردد. اگر
نتیجهٔ init نامعلوم و فقط intent موجود باشد، عملیات متوقف می‌شود؛ PV، Raft،
marker یا داده را برای retry حذف نکنید و از فولدر تازه برای دورزدن استفاده
نکنید. رمز اشتباه هم custody را حفظ می‌کند. پس از restart، manual Shamir دوباره
sealed می‌شود؛ resume فقط progress ناتمام حافظه‌ای را reset و دو سهم را وارد
می‌کند، نه datastore/key را. reboot واقعی در این تغییر انجام نمی‌شود.

## شواهد و ادامهٔ Stage 10

job موجود **Repository baseline / OpenBao TLS and Raft recovery** با OpenBao
2.6.4 پین‌شده، init PGP واقعی، export رمزدار، رمز اشتباه، decrypt از keyring
تازه، JSON stdin CLI، quorum یک/دو، منع reinit، restart، snapshot restore، ACL،
audit redaction و revoke root **مصنوعی** را اجرا می‌کند. به VPS/secret واقعی
دسترسی ندارد. unitها intent/partial failure، حفظ فایل، recipient/PVC mismatch
و منع plaintext را پوشش می‌دهند. CI یا سند، شاهد اجرای هدف نیست.

snapshot ساعتی رمزدار خارج PVC/میزبان و restore هدف، bounded audit export/rotation،
scoped auth/ESO، لغو root token اولیه پس از ایجاد و آزمایش مسیر مدیریتی جایگزین،
JIT و حذف دسترسی دائمی، GitOps و دیگر گیت‌های ظرفیت/DR/برنامه هنوز لازم‌اند.
root token اولیه فقط رمزدار تحویل داده می‌شود و در این مرحله revoke نمی‌شود؛
استفادهٔ روزمره از آن ممنوع است. ترافیک Production/دادهٔ واقعی باز نمی‌شود.

مرجع: [OpenBao 2.6.4 sys/init](https://github.com/openbao/openbao/blob/v2.6.4/website/content/docs/api/system/init.mdx)
و [JSON stdin write](https://github.com/openbao/openbao/blob/v2.6.4/command/write.go).
