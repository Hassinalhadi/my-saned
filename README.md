# Saned Assistant (مشروع نظيف ومفتوح المصدر)

مشروع أندرويد مستقل لبناء وتطوير موديول مساعدة مناديب جاهز (`net.jahez.fleets`) عبر LSPosed.

### المميزات:
1. **بدون شاشات قفل أو تراخيص**: يعمل مباشرة على أي جهاز بدون قيود Signature أو License Tokens.
2. **حل مشكلة `location unknown`**: يحتوي على خيار `acceptWhenLocationUnknown` لتفادي تفويت الطلبات عند تعذر قراءة الـ GPS اللحظي.
3. **جاهز للبناء الآلي على GitHub**: مدمج بـ GitHub Actions Workflow لبناء وتوقيع الـ APK بضغطة زر واحدة.

### طريقة الرفع والاستخدام على GitHub:
1. فك ضغط هذه الحزمة وارفع جميع الملفات مباشرة إلى مستودعك `my-saned`.
2. ادخل على تبويب **Actions** في GitHub وشغّل الـ Workflow.
3. حمّل ملف الـ APK الناتج من قسم **Artifacts** أو **Releases** وثبته على هاتفك وفعّله في LSPosed.
