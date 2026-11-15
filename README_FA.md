# LogScope AI — نسخه توسعه‌یافته 0.2.0

اپلیکیشن اندرویدی گرافیکی برای مشاهده، ذخیره، دسته‌بندی و تحلیل لاگ‌های قابل‌دسترسی دستگاه.

## ویژگی‌های پیاده‌سازی‌شده در سورس
- رابط گرافیکی Jetpack Compose با پنج بخش Home، Logs، Crashes، AI Lab و Settings
- حداقل Android 8.0 (API 26)، هدف تست Android 14
- اتصال Shizuku از طریق UserService و AIDL؛ بدون استفاده از API منسوخ `newProcess`
- دریافت زنده تمام بافرهای قابل‌دسترسی `logcat -b all -v threadtime` با هویت shell پس از تأیید کاربر
- تجزیه لاگ‌ها، تشخیص سطح و دسته‌بندی Crash/ANR/Network/Power/Permission/Memory
- ذخیره محلی با Room؛ نمایش آخرین ۳۰۰۰ ورودی و نگهداری حداکثر ۱۰۰۰۰ ورودی
- جست‌وجو، فیلتر سطح و دسته، بازکردن جزئیات و پاک‌سازی داده
- واردکردن فایل‌های متنی logcat و خروجی TXT، CSV و JSON از طریق Android document picker
- تحلیل محلی مبتنی بر قواعد و اتصال اختیاری به API سازگار با OpenAI از طریق HTTPS
- ماسک‌کردن الگوهای متداول ایمیل، شماره تلفن، Bearer token و API key پیش از ارسال به AI ابری
- تم تاریک/روشن

## ساخت در Android Studio
1. پوشه `LogScopeAI` را از طریق Android Studio باز کنید.
2. JDK 17، Android SDK Platform 36 و Build Tools سازگار را نصب کنید.
3. Gradle Sync را انجام دهید و سپس `app` را اجرا کنید.
4. روی گوشی Shizuku را نصب و اجرا کنید. در Android 11 به بالا می‌توان آن را با Wireless debugging راه‌اندازی کرد.
5. در LogScope AI مجوز Shizuku را بدهید و Start live capture را بزنید.

## محدودیت‌ها و نکات مهم
- لاگ‌گیری زنده فقط وقتی اپ در حال اجرا و در foreground است فعال می‌ماند؛ این نسخه سرویس foreground مستقل برای capture پس‌زمینه ندارد.
- دسترسی ADB با Shizuku معادل Root نیست. بعضی لاگ‌ها و داده‌های خصوصی برنامه‌های دیگر همچنان از دسترس خارج‌اند.
- حداقل API 26 به معنی تضمین تمام قابلیت‌های Shizuku در همه نسخه‌ها نیست؛ رفتار دسترسی‌ها به نسخه اندروید و نسخه Shizuku وابسته است.
- تحلیل محلی یک موتور heuristic است و تشخیص قطعی علت خطا محسوب نمی‌شود.
- Cloud AI اختیاری است؛ کلید API را در اختیار شخص دیگری قرار ندهید. پیش از ارسال لاگ‌ها، داده‌های حساس را بررسی کنید.
- تست‌های واحد برای parser و analyzer و redaction اضافه شده‌اند؛ اما در این محیط Android SDK/Gradle در دسترس نبود و Build یا اجرای تست‌ها انجام نشده است. بنابراین هنوز APK تأییدشده یا ادعای تست موفق نداریم. اولین Build و تست روی Android Studio باید انجام شود.

## نسخه‌ها
- Version name: 0.2.0
- Version code: 2
- minSdk: 26
- targetSdk: 35
- compileSdk: 36
- Kotlin: 2.2.21
- Shizuku API: 13.1.5
- Room: 2.8.5
