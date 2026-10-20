# Android Design Audit — Android Studio plugin

Plugin لـ Android Studio يفحص تطبيقات Jetpack Compose مقابل أساسيات Material Design 3 من داخل الـ IDE:
نافذة **Design Audit** تلتقط الشاشة من الجهاز مع *Show layout bounds*، تقيس المسافات وأحجام اللمس بالـ dp الحقيقية،
تفحص الكود، وتعرض المشاكل في جدول تضغط عليه مرتين لتذهب إلى السطر أو الصورة المرقّمة.

## البناء (مرة واحدة)

يلزم JDK 21+ واتصال إنترنت (Gradle سينزّل IntelliJ SDK ~800MB أول مرة). إذا كان `java` الافتراضي عندك أحدث من 24
(مثل JDK 25) استخدم JDK المدمج في Android Studio:

```bash
# macOS
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
# Windows (PowerShell)
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
```

```bash
cd android-design-audit-idea
./gradlew buildPlugin          # على Windows: gradlew.bat buildPlugin
```

الناتج: `build/distributions/android-design-audit-0.1.0.zip`

للتجربة بدون تثبيت: `./gradlew runIde` يفتح نسخة IntelliJ فيها الـ plugin.

> تُبنى مقابل IntelliJ Community 2024.2 (يكفي لأن الـ plugin يستخدم واجهات المنصة العامة فقط) وتعمل في
> Android Studio Ladybug 2024.2 وما بعده. إن أردت البناء مقابل Android Studio نفسه، غيّر السطر المعلّق في
> `build.gradle.kts` (`androidStudio("…")`).

## التثبيت في Android Studio

1. `File → Settings` (أو `Android Studio → Settings` على macOS) → **Plugins**
2. أيقونة الترس ⚙ → **Install Plugin from Disk…**
3. اختر ملف الـ zip من `build/distributions/`
4. أعد تشغيل Android Studio

## الاستخدام

1. وصّل جهازاً (USB debugging) أو شغّل محاكياً، وافتح الشاشة المطلوبة في تطبيقك.
2. افتح نافذة **Design Audit** من الشريط السفلي (أو `Tools → Android Design Audit`).
3. اختر الجهاز، اكتب اسم الشاشة (home، orders…) واضغط **Capture screen**.
   - يفعّل layout bounds، يلتقط `screen_bounds.png` و`screen_clean.png`، يسحب `hierarchy.xml`، يعيد الإعداد.
   - يقيس: أهداف اللمس < 48dp، الفجوة بين الأهداف < 8dp، هوامش الشاشة ≠ 16dp، المسافات خارج شبكة 4dp،
     المحاذاة غير المتطابقة، التداخل، عدد عناصر Bottom Navigation، العناصر التفاعلية بلا وصف،
     **الهامش الداخلي للكروت والصناديق** (أقل من 8dp، خارج الشبكة، أو مختلف بين كروت بنفس العرض).
   - يرسم `annotated.png` بأرقام تطابق عمود `#` في الجدول.
4. اضغط **Scan code** لفحص ملفات `.kt`: **كل `Text()` بدون `style` من الـ Typography** (يرسم بالخط الافتراضي)،
   `fontFamily` مكتوب داخل الشاشة بدل Type.kt، غياب أي FontFamily في المشروع، شبكة dp، sp مقابل dp، ألوان ونصوص يدوية، APIs لا تدعم RTL، أيقونات بلا
   AutoMirrored، أهداف لمس صغيرة، خلط Material 2/3، Toast… وعلى مستوى المشروع: edge-to-edge، الوضع الداكن،
   Dynamic Color، مقياس الخطوط، التخطيط المتكيف، `supportsRtl`، Predictive Back، Splash API، الأيقونة المتكيفة،
   حالات التحميل/الخطأ، اللغات.
5. اضغط مرتين على أي صف: ملف الكود يُفتح عند السطر، ومشكلة الشاشة تفتح الصورة المرقّمة.
   **Report** يفتح تقرير Markdown (`audit-captures/…/findings.md`، `code_findings.md`، أو `audit-report.md` المجمّع).

كل المخرجات تُحفظ في `audit-captures/` داخل مشروعك — أضفه إلى `.gitignore` إن أردت.

## أين adb؟

يبحث الـ plugin عن adb في: `local.properties` (`sdk.dir`) → `ANDROID_HOME` / `ANDROID_SDK_ROOT` → المسارات
المعتادة للـ SDK → `PATH`. إذا لم يجده، أضف `platform-tools` إلى الـ PATH أو عرّف `ANDROID_HOME`.

## الإصدار 0.5 — تحذيرات أثناء الكتابة

- المخالفات تظهر **مباشرة في المحرر أثناء الكتابة** (تسطير أحمر للأخطاء وأصفر للتحذيرات، الشرح والإصلاح عند التمرير)
  وفي نافذة Problems — بدون ضغط أي زر. يمكن إيقافها من Settings → "Highlight violations in the editor".
- القواعد على مستوى المشروع (الموارد غير المستخدمة، التكرار، الثيم…) تبقى في **Scan code**.

## الإصدار 0.4

- مستويان فقط: **Error** و**Warning** (لا Info). ملفات الاختبار (`src/test`, `src/androidTest`, `*Test.kt`…) مستثناة دائماً.
- قواعد جديدة — نظام التصميم: `spacing-literal`/`no-spacing-tokens`، `hardcoded-shape`/`no-shape-tokens`، `hardcoded-elevation`،
  `hardcoded-textstyle`، `icon-size`، `button-height`، `card-clickable`، `custom-dialog`، `deprecated-api`، `empty-click`،
  `todo-left`، `string-via-context`. نظافة الكود: `unused-import`، `unused-code`، `unused-resource`، `duplicate-string`،
  `duplicate-image`، `large-image`، `large-vector`.
- نفس المحرك متاح كـ CLI (`audit-core.jar`: `review --git` للملفات المتغيرة، `audit` للمشروع، `hierarchy` للالتقاط) وكـ skill لـ Claude.

## هوامش الشاشة (Design system)

- القيمة المرجعية لهامش الشاشة في **Settings → Screen side margin** (الافتراضي 16dp). غيّرها لقيمة نظام التصميم عندك
  (مثلاً 20dp) وسيُقاس كل شيء مقابلها.
- أي محتوى خارجي (مباشرة داخل عمود الشاشة) يبعد عن الحافة مسافة ≠ الهامش يُبلَّغ عنه كـ **warning**، مع تحذير ملخّص
  على مستوى الشاشة ("معظم المحتوى على بعد 20dp بدل 16dp"). القيم المقبولة: الهامش نفسه، 24dp للشاشات الكبيرة،
  وضعف الهامش (محتوى داخل كرت يقع على الهامش). أزرار الأيقونات (48dp) تُستثنى لأنها تقع عند الهامش−12 بحسب Material.
- ملاحظة Compose: `uiautomator` لا يُظهر إلا العناصر التي لها semantics (نص، contentDescription، clickable…). إذا كان
  كرت أو صندوق لا يظهر في القياس أضف `Modifier.semantics { testTagsAsResourceId = true }` على جذر الشاشة مع
  `Modifier.testTag("card")` على الحاوية، فتظهر في الشجرة ويُقاس هامشها الداخلي.

## الأداء (Performance)

- **Measure perf** (أو `Tools → Android Design Audit → Measure Performance`، `Ctrl+Alt+Shift+P`): يصفّر عدّادات الإطارات،
  يعطيك 10 ثوانٍ تتفاعل فيها مع التطبيق (تمرير، فتح شاشات)، ثم يقرأ `dumpsys gfxinfo`: نسبة الإطارات المتقطعة (janky)،
  أزمنة الإطار p50/p90/p95/p99، الإطارات البطيئة على خيط الواجهة، missed vsync، رفع الصور البطيء، والذاكرة (PSS).
  كما يُقرأ تلقائياً مع كل **Capture screen**.
- **فحص الكود** يكتشف أنماط Compose التي تسبب إعادة تركيب (recomposition) زائدة أو عملاً في كل إطار:
  `mutableStateOf` بدون `remember`، قوائم Lazy بدون `key`، `Column(verticalScroll)` مع `forEach`، LazyColumn داخل
  verticalScroll، قراءة حالة/أنيميشن داخل `Modifier.offset/alpha/scale(...)` بدل النسخة lambda، إنشاء كائنات ثقيلة
  (SimpleDateFormat/Regex/Gson) داخل التركيب، `sortedBy/filter` في التركيب، `runBlocking`/`Thread.sleep`، فك ترميز Bitmap،
  `collectAsState` بدل `collectAsStateWithLifecycle`، `LaunchedEffect` بمفتاح lambda، `GlobalScope`، معاملات `List` غير
  مستقرة، `Modifier.composed`.
- عدد مرات الـ recomposition نفسه لا يُقرأ عبر adb؛ عندما تظهر نسبة jank عالية استخدم Layout Inspector
  (Recomposition counts) على الشاشة المعنية — التقرير يوجّهك لذلك.

## ما في النافذة

- **Device / Screen / Capture screen / Scan code** في الأعلى: الخطوة الأساسية بزر واحد.
- شريط أدوات الـ IDE: Capture + Scan معاً، تحديث الأجهزة، فتح الصورة المرقّمة، التقرير (Markdown)، تقرير HTML
  للمشاركة (الصور مضمّنة)، مسح النتائج، الإعدادات (حدود 48/8/16dp، مجلد الإخراج، خيارات الالتقاط).
- فلاتر Errors / Warnings / Info + بحث نصي فوري.
- جدول النتائج (#، الخطورة، المجال، القاعدة، الموقع، المشكلة). اختر صفاً لترى في اللوحة اليمنى:
  **لماذا يهم** و**كيف تصلحه** مع الكود المقتبس، ومعاينة لقطة الشاشة مع تظليل العنصر المعني (Annotated / Layout bounds / Clean).
- زر **Export .md** (أو `Tools → Android Design Audit → Export Markdown…`، `Ctrl+Alt+Shift+E`): يحفظ كل النتائج بلا اقتطاع، لكل واحدة الموقع والكود و**لماذا تهم** و**كيف تُصلح**، مع ملخص بالمجالات وقائمة Checklist للمتابعة.
- ضغطتان = فتح الملف عند السطر أو الصورة الكاملة. اختصارات: `Ctrl+Alt+Shift+A` التقاط، `Ctrl+Alt+Shift+S` فحص الكود.
- حالة فارغة توجّهك عندما لا توجد نتائج بعد، وإشعارات بعد كل عملية مع زر فتح سريع.

## الهيكل

```
src/main/kotlin/com/kareem/designaudit/
├── adb/Adb.kt                    # تشغيل adb: الأجهزة، layout bounds، screencap، uiautomator dump، معلومات الجهاز
├── analysis/HierarchyAnalyzer.kt # قواعد القياس من شجرة العناصر (بالـ dp)
├── analysis/ComposeScanner.kt    # قواعد فحص الكود والمشروع
├── analysis/Annotator.kt         # رسم الصورة المرقّمة
├── analysis/Reports.kt           # تقارير Markdown و HTML
├── analysis/AuditRunner.kt       # تنسيق الالتقاط/الفحص وكتابة الملفات
├── analysis/AuditService.kt      # حالة المشروع، المهام الخلفية، الإشعارات، التنقل
├── model/Finding.kt              # Finding, Severity, DeviceInfo, Areas
├── settings/AuditSettings.kt     # الإعدادات المحفوظة
├── ui/AuditPanel.kt              # نافذة الأداة (جدول + فلاتر + حالة فارغة)
├── ui/DetailsPanel.kt            # تفاصيل المشكلة + معاينة الشاشة
├── ui/ScreenshotPreview.kt       # معاينة مع تظليل العنصر المحدد
├── ui/SettingsDialog.kt          # حوار الإعدادات
└── actions/Actions.kt            # إجراءات القائمة وشريط الأدوات
```

لتعديل الحدود (48dp، 8dp، 16dp…) استخدم حوار الإعدادات؛ ولإضافة قاعدة كود جديدة أضف Regex وسطر `add(...)`
في `ComposeScanner.scanFile`.
