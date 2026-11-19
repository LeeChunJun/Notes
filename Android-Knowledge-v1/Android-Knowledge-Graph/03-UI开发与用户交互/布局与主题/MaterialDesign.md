---
tags: [android]
part: 第3部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[ConstraintLayout]], [[主题与样式]], [[../README]]"
---

# MaterialDesign

## 一句话定义

Material Design 是 Google 提出的**跨平台 UI 设计语言**，自 Android 5.0（Lollipop）起成为系统设计规范，并在 Material 1 → 2 → 3 的演进中不断演进。Android 上的实现分两套：**View 体系的 `com.google.android.material`（Material Components）**与 **Compose 体系的 `androidx.compose.material3`**。

## 核心原理

### 1. Material Design 1 / 2 / 3 演进

| 版本 | 时间 | 核心特征 | Android 上的实现 |
| --- | --- | --- | --- |
| **Material 1（MD1）** | 2014（Android 5.0） | 引入 **Roboto** 字体、**Material Theme**（`colorPrimary` 等）、**Material Elevation**（高度阴影）、**FAB**、**Snackbar**、**Bottom Navigation**、卡片 | `com.google.android.material:material` 1.0（2018） |
| **Material 2（MD2）** | 2018 | 引入 **Material Theming（动态取色）**、更精细的 **组件规格**、**Elevation 体系细化**、**Shape 体系**、组件 API 大改（`TextInputLayout` 取代 `TextInputEditText` 组合） | `com.google.android.material:material` 1.1+ |
| **Material 3（MD3）** | 2021 | 引入 **动态取色（Dynamic Color）**、**新色板与色彩角色（Color Roles）**、**新的形状/字号/动效体系**、**M3 Expressive**（2025） | `com.google.android.material:material` 1.5+ / Compose `material3` |

**MD3 的关键变化**：

1. **色彩角色（Color Roles）**：不再直接用"红色/蓝色"，而是定义**语义角色**：
   ```text
   primary / onPrimary / primaryContainer / onPrimaryContainer
   secondary / onSecondary / secondaryContainer / onSecondaryContainer
   tertiary / onTertiary / tertiaryContainer / onTertiaryContainer
   error / onError / errorContainer / onErrorContainer
   background / onBackground
   surface / onSurface / surfaceVariant / onSurfaceVariant
   surfaceContainer / surfaceContainerHigh / surfaceContainerLow ...
   outline / outlineVariant
   inverseSurface / inverseOnSurface / inversePrimary
   ```
2. **动态取色**：Android 12+ 可从壁纸取色生成主题（`DynamicColors.applyToActivityIfAvailable()`）。
3. **形状体系**：MD2 的圆角（4dp/8dp/16dp）→ MD3 更柔和、更大的圆角。
4. **字号体系**：MD3 使用了更完整的 `TextTheme` 层级。

### 2. Material Components 核心组件

| 组件 | 类名 | 作用 |
| --- | --- | --- |
| 工具栏 | `MaterialToolbar` | 替代 `Toolbar`/`ActionBar`，支持 logo、导航图标、菜单 |
| 文本输入框 | `TextInputLayout` | 浮动标签、字数计数、密码切换、错误提示 |
| 按钮 | `MaterialButton` | 支持 icon、shape、stroke、加载状态 |
| 卡片 | `MaterialCardView` / `MaterialCardView` | 支持 `strokeWidth`、`checkedIcon`、`cornerRadius` |
| 底部导航 | `BottomNavigationView` | 2~5 个 item，配合 `NavigationView` 使用 |
| 底部弹窗 | `BottomSheetDialog` / `BottomSheetBehavior` | 可拖拽展开的底部面板 |
| 提示条 | `Snackbar` | 短暂的、操作性反馈（比 Toast 强） |
| 侧边导航 | `NavigationView` | 抽屉式导航，与 `DrawerLayout` 配合 |
| 悬浮按钮 | `FloatingActionButton` / `ExtendedFloatingActionButton` | 页面主要动作 |
| 选项卡 | `TabLayout` | 与 `ViewPager2` 联动 |
| 开关 | `SwitchMaterial` | Material 风格开关 |
| 滑块 | `Slider` | 连续值输入 |
| 芯片 | `Chip` / `ChipGroup` | 标签、筛选、单选 |
| 进度条 | `LinearProgressIndicator` / `CircularProgressIndicator` | MD3 风格进度 |
| 主题覆盖 | `Theme.Material3.*` / `Theme.MaterialComponents.*` | 主题入口 |

**典型 XML 用法**：

```xml
<!-- 文本输入框：TextInputLayout 包裹 TextInputEditText -->
<com.google.android.material.textfield.TextInputLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:hint="@string/email_hint"
    app:boxBackgroundMode="outline"
    app:counterEnabled="true"
    app:counterMaxLength="50"
    app:endIconMode="password_toggle"
    app:errorEnabled="true">

    <com.google.android.material.textfield.TextInputEditText
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:inputType="textEmailAddress" />
</com.google.android.material.textfield.TextInputLayout>
```

```xml
<!-- MaterialButton：带图标、圆角、描边 -->
<com.google.android.material.button.MaterialButton
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="@string/submit"
    app:icon="@drawable/ic_send"
    app:iconGravity="textStart"
    app:cornerRadius="20dp"
    app:strokeWidth="1dp"
    app:strokeColor="?attr/colorOutline"
    style="@style/Widget.Material3.Button.OutlinedButton" />
```

### 3. 主题属性与颜色角色

**Material 主题的属性命名**：

```xml
<style name="Theme.Material3.DayNight" parent="Theme.Material3.DayNight.NoActionBar">
    <!-- 颜色 -->
    <item name="colorPrimary">@color/md_theme_primary</item>
    <item name="colorOnPrimary">@color/md_theme_onPrimary</item>
    <item name="colorPrimaryContainer">@color/md_theme_primaryContainer</item>
    <item name="colorOnPrimaryContainer">@color/md_theme_onPrimaryContainer</item>
    <item name="colorSecondary">@color/md_theme_secondary</item>
    <item name="colorSurface">@color/md_theme_surface</item>
    <item name="colorOnSurface">@color/md_theme_onSurface</item>
    <item name="colorError">@color/md_theme_error</item>
    <item name="colorOnError">@color/md_theme_onError</item>
    <item name="colorOutline">@color/md_theme_outline</item>

    <!-- 形状 -->
    <item name="shapeAppearanceCornerSmall">@style/ShapeAppearance.Material3.Corner.Small</item>
    <item name="shapeAppearanceCornerMedium">@style/ShapeAppearance.Material3.Corner.Medium</item>
    <item name="shapeAppearanceCornerLarge">@style/ShapeAppearance.Material3.Corner.Large</item>

    <!-- 字体 -->
    <item name="textAppearanceHeadlineLarge">@style/TextAppearance.Material3.Headline.Large</item>

    <!-- 组件样式 -->
    <item name="materialButtonStyle">@style/Widget.Material3.Button</item>
    <item name="textInputStyle">@style/Widget.Material3.TextInputLayout.OutlinedBox</item>
    <item name="bottomSheetDialogTheme">@style/ThemeOverlay.Material3.BottomSheetDialog</item>
</style>
```

**关键规则**：**"on" 前缀的颜色表示"在这个背景上应该用什么文字颜色"**。

```text
colorPrimary        = 品牌主色（深）
colorOnPrimary      = 放在 colorPrimary 上的文字颜色（通常是白色）
colorPrimaryContainer = 主色的浅色容器
colorOnPrimaryContainer = 放在容器上的文字颜色（通常是深色）
```

这个规则保证了**对比度（contrast）**，是 Material 主题设计的精髓。

**MD2 的旧命名 vs MD3**：

| MD2 | MD3 |
| --- | --- |
| `colorPrimary` / `colorOnPrimary` | 同名 |
| `colorSecondary` | `colorSecondary` + `colorSecondaryContainer` |
| `colorSurface` / `colorBackground` | 统一为 `colorSurface`，`colorSurfaceVariant` 单独定义 |
| `colorError` | 同名（MD3 增加了 `colorErrorContainer`） |
| `textAppearanceHeadline6` | `textAppearanceHeadlineLarge` |
| `shapeAppearanceSmallComponent` | `shapeAppearanceCornerSmall` |

### 4. 深色主题资源

Material 官方提供两套完整主题：`Theme.Material3.Light` 与 `Theme.Material3.Dark`，以及 `DayNight`（跟随系统）。

**自定义主题的推荐做法**：

```xml
<!-- values/colors.xml -->
<resources>
    <color name="md_theme_primary">#6750A4</color>
    <color name="md_theme_onPrimary">#FFFFFF</color>
    <color name="md_theme_primaryContainer">#EADDFF</color>
    <color name="md_theme_onPrimaryContainer">#21005D</color>
    <color name="md_theme_surface">#FFFBFE</color>
    <color name="md_theme_onSurface">#1C1B1F</color>
    <color name="md_theme_error">#B3261E</color>
</resources>
```

```xml
<!-- values-night/colors.xml -->
<resources>
    <!-- 深色模式使用同一批 name，但不同值 -->
    <color name="md_theme_primary">#D0BCFF</color>
    <color name="md_theme_onPrimary">#381E72</color>
    <color name="md_theme_primaryContainer">#4F378B</color>
    <color name="md_theme_onPrimaryContainer">#EADDFF</color>
    <color name="md_theme_surface">#1C1B1F</color>
    <color name="md_theme_onSurface">#E6E1E5</color>
    <color name="md_theme_error">#F2B8B5</color>
</resources>
```

**MD3 深色的关键规则**：
- 深色模式下**主色变浅**（`primary` 变亮、`onPrimary` 变深），以保证在深色背景上的对比度。
- 容器色（`*Container`）在深色模式下**变深**（而不是变浅），这与直觉相反但符合 M3 规范。

### 5. Material 主题的继承体系

```text
Theme.Material3.DayNight
 ├── Theme.Material3.DayNight.NoActionBar
 ├── Theme.Material3.Light
 ├── Theme.Material3.Light.NoActionBar
 ├── Theme.Material3.Dark
 ├── Theme.Material3.Dark.NoActionBar
 └── ThemeOverlay.Material3.*    ← 用于局部覆盖（如 BottomSheetDialog）

Theme.MaterialComponents.*        ← MD2 旧版
Theme.AppCompat.*                ← 兼容老应用（仅提供基础控件）
```

**`ThemeOverlay` 的用途**：只覆盖少量属性，用于**局部**主题（如 Dialog、BottomSheet），不影响整个 Activity。

```xml
<style name="ThemeOverlay.App.BottomSheetDialog" parent="ThemeOverlay.Material3.BottomSheetDialog">
    <item name="bottomSheetStyle">@style/Widget.App.BottomSheet.Modal</item>
</style>
```

### 6. 动态取色（Dynamic Color）

```kotlin
// Android 12+ (API 31)
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    DynamicColors.applyToActivityIfAvailable(this)
}
```

- 从壁纸提取主色，生成 Material 3 主题。
- 需要在 `values-v31/themes.xml` 中额外配置，或直接调用上述 API。

### 7. Compose Material3 与 Material2 的差异

| 维度 | Material2 (`androidx.compose.material`) | Material3 (`androidx.compose.material3`) |
| --- | --- | --- |
| 色彩 | `MaterialTheme.colors` | `MaterialTheme.colorScheme` |
| 主色 | `primary` | `primary` |
| 背景 | `background` | `surface` |
| 变体色 | `primaryVariant` | `primaryContainer` |
| 错误色 | `error` | `error` |
| 字体 | `typography` | `typography`（MD3 体系） |
| 形状 | `shapes` | `shapes` |
| 组件 | `Scaffold`/`AppBar`/`Text` | `Scaffold`/`TopAppBar`/`Text` |
| 新特性 | — | `ModalBottomSheet`、`SearchBar`、`DatePicker`、`TimePicker`、`Snackbar`、M3 动效 |
| 状态 | 维护模式（deprecated） | 活跃 |

**Material2 → Material3 迁移要点**：

```kotlin
// Material2
val colors = MaterialTheme.colors
Text(color = colors.primary)
Surface(color = colors.background)

// Material3
val colors = MaterialTheme.colorScheme
Text(color = colors.primary)
Surface(color = colors.surface)
```

**M3 组件示例**：

```kotlin
@Composable
fun M3Screen() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("标题") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { /* ... */ }) {
                Icon(Icons.Default.Add, contentDescription = "添加")
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("首页") }
                )
            }
        }
    ) { padding ->
        LazyColumn(contentPadding = padding) { /* ... */ }
    }
}
```

### 8. Material 与 AppCompat 的区别

| 维度 | AppCompat | Material Components |
| --- | --- | --- |
| 定位 | **向后兼容** Android 5.0 以下的旧控件 | 实现 **Material Design** 规范 |
| 控件 | `AppCompatButton`、`AppCompatTextView`、`Toolbar` | `MaterialButton`、`TextInputLayout`、`MaterialToolbar`、`Snackbar` |
| 依赖 | `androidx.appcompat:appcompat` | `com.google.android.material:material` |
| 主题 | `Theme.AppCompat.*` | `Theme.Material3.*` / `Theme.MaterialComponents.*` |
| 关系 | Material Components **依赖** AppCompat | ✅ |

**选型建议**：
- **新项目** → `Theme.Material3.*` + Material Components。
- **老项目升级** → `Theme.MaterialComponents.*`（提供从 AppCompat 主题的迁移路径：`Theme.MaterialComponents.DayNight.DarkActionBar` 的 parent 就是 AppCompat 主题）。
- **只有极简需求**（无 Material 组件需求）→ AppCompat 足够。

## 代码示例

### 1. 完整的 Material 3 主题定义

```xml
<!-- res/values/themes.xml -->
<resources xmlns:tools="http://schemas.android.com/tools">

    <style name="Theme.MyApp" parent="Theme.Material3.DayNight.NoActionBar">
        <!-- 颜色角色 -->
        <item name="colorPrimary">@color/md_theme_primary</item>
        <item name="colorOnPrimary">@color/md_theme_onPrimary</item>
        <item name="colorPrimaryContainer">@color/md_theme_primaryContainer</item>
        <item name="colorOnPrimaryContainer">@color/md_theme_onPrimaryContainer</item>
        <item name="colorSecondary">@color/md_theme_secondary</item>
        <item name="colorOnSecondary">@color/md_theme_onSecondary</item>
        <item name="colorError">@color/md_theme_error</item>
        <item name="colorOnError">@color/md_theme_onError</item>
        <item name="colorSurface">@color/md_theme_surface</item>
        <item name="colorOnSurface">@color/md_theme_onSurface</item>
        <item name="colorSurfaceVariant">@color/md_theme_surfaceVariant</item>
        <item name="colorOnSurfaceVariant">@color/md_theme_onSurfaceVariant</item>
        <item name="colorOutline">@color/md_theme_outline</item>

        <!-- 系统栏（Edge-to-edge 场景） -->
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:windowLightStatusBar" tools:targetApi="m">true</item>

        <!-- 组件样式 -->
        <item name="materialButtonStyle">@style/Widget.MyApp.Button</item>
        <item name="bottomSheetDialogTheme">@style/ThemeOverlay.MyApp.BottomSheetDialog</item>
    </style>

    <!-- 局部覆盖：用于 BottomSheet -->
    <style name="ThemeOverlay.MyApp.BottomSheetDialog" parent="ThemeOverlay.Material3.BottomSheetDialog">
        <item name="bottomSheetStyle">@style/Widget.MyApp.BottomSheet</item>
    </style>
</resources>
```

```kotlin
// 动态取色（Android 12+）
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        setContentView(R.layout.activity_main)
    }
}
```

### 2. Compose Material3 主题

```kotlin
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 1. 定义色板
val LightColors = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    onSecondary = Color.White,
    tertiary = Color(0xFF7D5260),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E)
)

val DarkColors = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    tertiary = Color(0xFFEFB8C8),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99)
)

val MyTypography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
)

val MyShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MyAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MyTypography,
        shapes = MyShapes,
        content = content
    )
}
```

### 3. Material 组件使用示例（View 体系）

```xml
<!-- 完整示例：BottomNavigationView + Snackbar + FAB + BottomSheet -->
<androidx.coordinatorlayout.widget.CoordinatorLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <com.google.android.material.appbar.AppBarLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:fitsSystemWindows="true">

        <com.google.android.material.appbar.MaterialToolbar
            android:id="@+id/toolbar"
            android:layout_width="match_parent"
            android:layout_height="?attr/actionBarSize"
            app:title="@string/app_name"
            app:logo="@drawable/ic_logo" />
    </com.google.android.material.appbar.AppBarLayout>

    <androidx.core.widget.NestedScrollView
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        app:layout_behavior="@string/appbar_scrolling_view_behavior">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical">

            <com.google.android.material.textfield.TextInputLayout
                android:id="@+id/emailLayout"
                style="@style/Widget.Material3.TextInputLayout.OutlinedBox"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:hint="@string/email">

                <com.google.android.material.textfield.TextInputEditText
                    android:id="@+id/emailInput"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:inputType="textEmailAddress" />
            </com.google.android.material.textfield.TextInputLayout>

            <com.google.android.material.button.MaterialButton
                android:id="@+id/submit"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="16dp"
                android:text="@string/submit" />
        </LinearLayout>
    </androidx.core.widget.NestedScrollView>

    <com.google.android.material.floatingactionbutton.FloatingActionButton
        android:id="@+id/fab"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|end"
        android:layout_margin="16dp"
        android:contentDescription="@string/add"
        app:srcCompat="@drawable/ic_add" />

    <com.google.android.material.bottomnavigation.BottomNavigationView
        android:id="@+id/bottomNav"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom"
        app:menu="@menu/bottom_nav_menu" />

</androidx.coordinatorlayout.widget.CoordinatorLayout>
```

```kotlin
// View 体系中的 Material 组件交互
class MainActivity : AppCompatActivity() {

    private lateinit var emailLayout: TextInputLayout
    private lateinit var bottomSheet: BottomSheetBehavior<View>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        emailLayout = findViewById(R.id.emailLayout)
        findViewById<MaterialButton>(R.id.submit).setOnClickListener {
            val email = emailLayout.editText?.text?.toString().orEmpty()
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                emailLayout.error = getString(R.string.invalid_email)
                return@setOnClickListener
            }
            emailLayout.error = null
            Snackbar.make(it, R.string.submit_success, Snackbar.LENGTH_LONG)
                .setAction(R.string.undo) { /* 撤销 */ }
                .setAnchorView(findViewById(R.id.bottomNav))   // 避免遮挡底部导航
                .show()
        }

        // BottomSheet
        val sheet = findViewById<FrameLayout>(R.id.sheet)
        bottomSheet = BottomSheetBehavior.from(sheet).apply {
            state = BottomSheetBehavior.STATE_HALF_EXPANDED
            peekHeight = 300
        }
    }
}
```

### 4. Compose Material3 组件示例

```kotlin
@Composable
fun Material3Demo() {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Material 3") },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    action = {
                        TextButton(onClick = { /* undo */ }) { Text("撤销") }
                    }
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showSheet = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("新建") }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = true,
                    onClick = { },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("首页") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = { },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("设置") }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // M3 芯片组
            FilterChip(
                selected = true,
                onClick = { },
                label = { Text("筛选") },
                leadingIcon = {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                }
            )
        }
    }

    // M3 底部弹窗
    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(Modifier.padding(24.dp)) {
                Text("底部弹窗内容", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
```

### 5. 自定义 Material 组件样式

```xml
<!-- 全局覆盖按钮样式 -->
<style name="Widget.MyApp.Button" parent="Widget.Material3.Button">
    <item name="android:minHeight">52dp</item>
    <item name="cornerRadius">16dp</item>
    <item name="android:textAppearance">@style/TextAppearance.MyApp.Button</item>
    <item name="backgroundTint">?attr/colorPrimaryContainer</item>
    <item name="android:textColor">?attr/colorOnPrimaryContainer</item>
</style>

<!-- 局部应用（不污染全局） -->
<com.google.android.material.button.MaterialButton
    style="@style/Widget.MyApp.Button"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="确定" />
```

```kotlin
// Kotlin 方式局部覆盖样式
MaterialButton(
    onClick = { },
    shape = RoundedCornerShape(16.dp),
    colors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ),
    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
) {
    Text("确定")
}
```

## 常见问题 / 面试要点

**读者问：Material Design 1/2/3 有哪些主要区别？Material 2 到 3 的变化点在哪里？**

**AI答：**

1. **MD1（2014）**：引入 Material Theme（`colorPrimary` 等）、Elevation 阴影体系、Roboto 字体、FAB、Snackbar、Bottom Navigation、Card。核心是"**用阴影和高度表达层级**"。
2. **MD2（2018）**：
   - 引入 **Material Theming**（取色器生成主题）；
   - 组件规格更细化（按钮高度、内边距、字号有明确数值）；
   - **Shape 体系**（圆角分级）；
   - API 大改：`TextInputEditText` 需包在 `TextInputLayout` 内、`MaterialButton` 替代 `AppCompatButton`。
3. **MD3（2021）**：
   - **色彩角色体系**：`primary` / `onPrimary` / `primaryContainer` / `onPrimaryContainer`，强调"**on 前缀 = 背景上的前景色**"，用语义而不是具体颜色名。
   - **动态取色**（Android 12+ 从壁纸取色）。
   - **形状更柔和**：圆角更大（`extraLarge` 达 28dp）。
   - **新的排版体系**：`displayLarge` → `bodySmall` 共 15 级。
   - **新的动效规范**：M3 Motion（4~5 个缓动曲线）。
   - **组件新增**：`ModalBottomSheet`、`SearchBar`、`DatePicker`、`TimePicker`、`Chip`。
4. **MD3 深色模式的反直觉点**：**容器色在深色模式下变深**（而不是变浅）；主色在深色模式下**变浅**。这与"深色 = 减少亮度"的直觉相反，是为了保证 `on*` 前景色的对比度。
5. **面试话术**："**MD1 定调『用高度表达层级』，MD2 细化规格并引入主题取色，MD3 引入『色彩角色』并把形状、动效整体重做。**"

---

**读者问：Material 与 AppCompat 有什么区别？`Theme.Material3.*` 和 `Theme.AppCompat.*` 该怎么选？**

**AI答：**

1. **本质区别**：
   - **AppCompat**（`androidx.appcompat`）：**向后兼容层**，把 Android 5.0+ 的新控件 API 回移植到低版本（`AppCompatActivity`、`AppCompatTextView`、矢量图支持、`Toolbar` 等）。它**不提供 Material Design 视觉**。
   - **Material Components**（`com.google.android.material`）：**Material Design 的实现**，依赖 AppCompat，提供全套 Material 组件与主题。
2. **依赖关系**：Material Components **依赖** AppCompat（引入它就会带上 AppCompat）。
3. **主题对应**：
   | AppCompat | Material |
   | --- | --- |
   | `Theme.AppCompat.Light.NoActionBar` | `Theme.Material3.Light.NoActionBar` |
   | `Theme.AppCompat.DayNight` | `Theme.Material3.DayNight` |
   | `Theme.AppCompat.NoActionBar` | `Theme.MaterialComponents.NoActionBar`（MD2） |
4. **选型建议**：
   - **新项目** → `Theme.Material3.*` + Material Components。
   - **老项目升级** → 先切到 `Theme.MaterialComponents.*`（它的 parent 链是 AppCompat 主题，迁移成本最低），再逐步切 MD3。
   - **只要兼容不要 Material 视觉** → AppCompat 足够（包更小）。
   - **主题 parent 不匹配会导致崩溃**：`Theme.Material3.*` 的 Activity 必须继承 `AppCompatActivity`（或 `ComponentActivity` + Compose），继承原生 `Activity` 会因为缺少 Material 主题属性而崩。
5. **常见坑**：
   - 用了 `MaterialButton` 但主题是 `Theme.AppCompat.*` → 运行时找不到 `materialButtonStyle` → 崩溃或样式错乱。
   - 主题是 `Theme.Material3.*` 但 Activity 继承 `Activity`（不是 `AppCompatActivity`）→ `AppCompatDelegate` 相关崩溃。
6. **面试话术**："**AppCompat 解决『旧系统用新控件』，Material 解决『界面长什么样』。新项目直接上 Material3，老项目先 MaterialComponents 过渡。**"

---

**读者问：Material 主题的颜色角色（color role）是什么？`colorPrimary` 和 `colorOnPrimary` 有什么区别？**

**AI答：**

1. **颜色角色的设计思想**：MD3 **不再用具体颜色名**（红、蓝、黄），而是用**语义角色**，让组件知道"我该用哪个颜色"，从而支持**动态换肤**（壁纸取色）与**深色模式自动适配**。
2. **核心规则**：
   - `colorXxx`：**背景色**（填充）。
   - `colorOnXxx`：**放在 `colorXxx` 上的前景色**（文字/图标）。
   - `colorXxxContainer`：**低强调度的容器背景**（大面积、弱化的色块）。
   - `colorOnXxxContainer`：容器上的前景色。
3. **举例**：
   ```text
   colorPrimary = #6750A4   （深紫，主按钮背景）
   colorOnPrimary = #FFFFFF （白，主按钮上的文字）
   colorPrimaryContainer = #EADDFF （浅紫，选中态背景）
   colorOnPrimaryContainer = #21005D （深紫，选中态文字）
   ```
4. **为什么这样设计**：**保证对比度**。应用只指定 4 个颜色，系统就能推导出整套配色，且一定满足 WCAG 无障碍对比度要求。
5. **MD2 vs MD3 的命名差异**：
   | MD2 | MD3 |
   | --- | --- |
   | `colorPrimary` / `colorOnPrimary` | 同名 |
   | `colorPrimaryVariant` | 拆成 `primaryContainer` / `onPrimaryContainer` |
   | `colorSecondary` / `colorSecondaryVariant` | 同上拆分 |
   | `colorBackground` | 统一为 `colorSurface` |
   | `textAppearanceHeadline6` | `textAppearanceHeadlineLarge` |
6. **面试话术**："**颜色角色 = 语义化的颜色接口。`on` 前缀代表『压在上面的颜色』，这是保证对比度的核心设计。**"

---

**读者问：深色模式下 Material 的颜色该怎么处理？为什么深色模式主色要变浅？**

**AI答：**

1. **处理方式**：**同名不同值**。在 `values-night/colors.xml` 中定义同名颜色的深色版本，主题引用不变。
   ```xml
   <!-- values/colors.xml -->
   <color name="md_theme_primary">#6750A4</color>
   <!-- values-night/colors.xml -->
   <color name="md_theme_primary">#D0BCFF</color>
   ```
2. **为什么主色要变浅**：
   - 深色背景（`surface` ≈ `#1C1B1F`）上需要**亮色**前景才看得清；
   - 如果主色仍是深紫 `#6750A4`，在深色背景上几乎不可见；
   - 所以深色模式把 `primary` **变浅**（`#D0BCFF`），同时把 `onPrimary` **变深**（`#381E72`）——因为浅色按钮上的文字要用深色。
3. **反直觉点：容器色在深色模式下变深**：
   - 浅色模式：`primaryContainer` 是**浅**紫（弱化但仍可见）；
   - 深色模式：`primaryContainer` 变成**深**紫（`#4F378B`），比 `surface` 略亮；
   - 原因：深色模式下"弱化"应该通过**降低对比度**而非提亮来实现。
4. **`DayNight` 主题**：使用 `Theme.Material3.DayNight`（或其子类），系统切换深色模式时自动切换资源。
5. **Compose 侧**：用 `lightColorScheme` / `darkColorScheme` + `isSystemInDarkTheme()` 切换（详见 [[../资源与适配/深色模式]]）。
6. **面试话术**："**深色模式不是简单反色，而是重新推导对比度：背景变暗 → 前景变亮 → on 颜色反向。**"

---

**读者问：Compose Material3 和 Material2 有什么差异？迁移时要注意什么？**

**AI答：**

1. **核心 API 差异**：
   | Material2 | Material3 |
   | --- | --- |
   | `MaterialTheme.colors` | `MaterialTheme.colorScheme` |
   | `colors.primary` | `colorScheme.primary` |
   | `colors.background` | `colorScheme.surface` |
   | `colors.onBackground` | `colorScheme.onSurface` |
   | `colors.primaryVariant` | `colorScheme.primaryContainer` |
   | `Colors()` 构造 | `lightColorScheme()` / `darkColorScheme()` |
2. **组件差异**：
   - M3 新增：`ModalBottomSheet`、`SearchBar`、`DatePicker`、`TimePicker`、`Chip`、`SegmentedButton`、`FloatingToolbar`。
   - `Scaffold` 在 M3 中支持 `TopAppBar`（M2 是 `TopAppBar` 在 material 包，M3 换成了 `androidx.compose.material3`）。
3. **迁移注意事项**：
   - **包名不同**：`androidx.compose.material.*` → `androidx.compose.material3.*`，**类名冲突**（`Text`、`Button` 等同名）→ 建议**改包名批量替换**而不是逐个 import。
   - **`Text` 的 `style` 默认值不同**：M2 默认 `bodyLarge`，M3 默认 `bodyLarge` 但字重/行高不同。
   - **`Button` 的默认高度/形状不同**。
   - **`Icons.Default.*` 需要 `material-icons-extended`**。
   - **M3 的 `Scaffold` 需要正确处理 `innerPadding`**，否则内容被系统栏遮挡。
4. **状态**：Material2 已进入**维护模式**（只修 bug），Material3 是**活跃开发**。
5. **面试话术**："**M2 → M3 不只是改颜色名，而是换了一整套语义色彩 API 和组件规格。新项目直接 M3。**"

---

**读者问：`Snackbar` 和 `Toast` 有什么区别？什么时候用哪个？**

**AI答：**

1. **核心区别**：
   | | `Toast` | `Snackbar` |
   | --- | --- | --- |
   | 样式 | 系统原生、无法定制 | Material 风格，可定制 |
   | 交互 | 不可交互、不可取消 | **可带操作按钮**（如"撤销"）、可滑动 dismiss |
   | 位置 | 固定 | 可 `setAnchorView` 避免遮挡底部导航 |
   | 队列 | 多个会排队 | 可替换当前显示 |
   | 无障碍 | 支持 | 支持，且更符合无障碍规范 |
2. **Material 规范**：**Toast 不应承载重要信息**（用户无法操作、无法确认）；Snackbar 用于**操作反馈**（保存成功、网络错误、可撤销操作）。
3. **最佳实践**：
   ```kotlin
   Snackbar.make(view, R.string.saved, Snackbar.LENGTH_SHORT)
       .setAction(R.string.undo) { viewModel.undo() }
       .setAnchorView(binding.bottomNav)     // 避免遮挡底部导航
       .show()
   ```
4. **Compose**：`SnackbarHost` + `SnackbarHostState.showSnackbar()`（suspend 函数）。
5. **面试话术**："**Toast 是『我告诉你一声』，Snackbar 是『我告诉你一声，而且你可以撤销』。可操作反馈一律用 Snackbar。**"

---

**读者问：Material 组件是怎么做到跟随主题换肤的？`defStyleAttr` 起了什么作用？**

**AI答：**

1. **机制链路**：
   ```text
   主题 Theme.Material3.DayNight
     └─ 定义 materialButtonStyle → @style/Widget.Material3.Button
          └─ 定义 backgroundTint → ?attr/colorPrimary
               └─ ?attr 在运行时从当前主题解析出具体颜色
   ```
2. **组件侧的机制**：Material 组件的默认样式不是写死的，而是：
   ```java
   // MaterialButton 的构造
   int defStyleAttr = R.attr.materialButtonStyle;
   // 再通过 defStyleRes 叠加一层
   TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.MaterialButton, defStyleAttr, defStyleRes);
   ```
   → **组件的默认属性来自当前主题**。
3. **`defStyleAttr` vs `defStyleRes`**：
   - `defStyleAttr`：指向**主题属性**（`R.attr.materialButtonStyle`）→ 从**当前主题**解析。
   - `defStyleRes`：指向**具体资源**（`R.style.Widget_MaterialButton`）→ 作为兜底。
   - 优先级：`defStyleRes` < `defStyleAttr` 指向的 style < XML 中显式设置。
4. **应用层的作用**：只要 App 换了主题（或动态取色），所有 Material 组件**自动跟着变**，**不需要改任何组件代码**。
5. **这也是 `ThemeOverlay` 存在的意义**：只想局部换（如 BottomSheet），用 `ThemeOverlay` 只覆盖少数属性，不影响全局。
6. **面试话术**："**Material 换肤的底层就是 `?attr` 引用 + `defStyleAttr` 主题属性解析，组件从不写死颜色，只写『我该用哪个角色』。**"

## 关联笔记

- [[ConstraintLayout]]：Material 组件内部的布局大量使用 ConstraintLayout
- [[主题与样式]]：主题属性的定义、继承与 `?attr` 引用
- [[../资源与适配/深色模式]]：`values-night` 与 Material 深色主题
- [[../动画/转场动画]]：Material Motion 转场（Shared Axis / Container Transform）
- [[../JetpackCompose/声明式UI与重组]]：Compose Material3 的重组特性
- [[../README]]：第 3 部分总览

## 参考资料

- Material Design 3 官方规范：https://m3.material.io/
- Google 官方文档 "Material Components for Android"：https://developer.android.com/develop/ui/views/components/material-3
- Google 官方文档 "Material Design 3 for Compose"：https://developer.android.com/develop/ui/compose/designsystems/material3
- Material Theme Builder：https://material-foundation.github.io/material-theme-builder/
- 《Android 高级编程》第 4 版 · Material 设计章节
