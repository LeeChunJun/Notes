---
tags: [android]
part: 第3部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[绘制流程]], [[事件分发机制]], [[../README]]"
---

# 自定义View

## 一句话定义

自定义 View 是通过继承 `View` / `ViewGroup` 并重写关键生命周期回调，绕开系统控件的固定外观，绘制或布局出完全自定义的界面。核心在于三点：**把握 `measure/layout/draw` 三个回调的契约**、**用 `attrs.xml` 把自定义属性暴露给 XML**、**在性能红线内作画（不在 `onDraw` 里分配对象、不随意 `requestLayout`）**。

## 核心原理

### 1. 自定义 View 的三类形态

| 类型 | 需要重写 | 典型场景 | 关键回调 |
| --- | --- | --- | --- |
| **绘制型** | `onMeasure` + `onDraw` | 图表、进度条、验证码、水印 | `onMeasure` / `onDraw` |
| **布局型** | `onMeasure` + `onLayout` | 自定义容器（流式布局、换行标签） | `onMeasure` / `onLayout` |
| **交互型** | `onTouchEvent`（+ `onDraw` 做状态反馈） | 滑块、开关、拖拽卡片、手势图表 | `onTouchEvent` / `dispatchTouchEvent` |
| **组合型**（ViewGroup） | `onMeasure` + `onLayout` + `dispatchDraw` | 上述的组合 | 全部 |

> 面试点：初学者常以为"自定义 View = 继承 View 重写 `onDraw`"，但真正完整的一个自定义控件至少要处理 **测量、布局、绘制、状态保存、触摸** 五件事。

### 2. 必备回调清单

```text
构造器 → onMeasure → onLayout → onDraw → onSaveInstanceState/onRestoreInstanceState
                                  ↕
                            onTouchEvent
```

| 回调 | 是否必须 | 说明 |
| --- | --- | --- |
| 构造器（3 个重载） | **必须** | 见下文"构造器链" |
| `onMeasure` | 视情况 | 尺寸不由父决定时必须重写 |
| `onLayout` | View 不需要 | ViewGroup **必须**实现，否则子 View 不显示 |
| `onDraw` | 绘制型必须 | 画内容；注意先调 `super.onDraw()` 才能画背景 |
| `onSaveInstanceState` | 建议 | 保存自定义状态（旋转屏幕后恢复） |
| `onRestoreInstanceState` | 建议 | 恢复上面保存的状态 |
| `onTouchEvent` | 交互型必须 | **记得 `setClickable(true)`** |
| `dispatchDraw` | ViewGroup 需注意 | 自定义绘制子 View 之前/之后的内容 |
| `drawableStateChanged` | 建议 | 响应选中/按下状态变化 |
| `onSizeChanged` | 建议 | 尺寸确定后初始化与尺寸相关的资源 |
| `onDetachedFromWindow` | 建议 | 停止动画、释放资源 |

**构造器链的三个重载**：

```java
public MyView(Context context)                              // 代码创建
public MyView(Context context, AttributeSet attrs)          // XML 创建（必须）
public MyView(Context context, AttributeSet attrs, int defStyleAttr)  // 带默认样式
```

- XML 中创建 View 时，系统只会调用第 2 个构造器 → **不实现它，`LayoutInflater` 会崩**。
- 第 3 个参数 `defStyleAttr` 是 `R.attr.xxxStyle` 形式的下层主题属性，让控件能被主题统一换肤（Material 控件就是这么做的）。

### 3. 自定义属性（attrs.xml）

```xml
<!-- res/values/attrs.xml -->
<resources>
    <declare-styleable name="RingProgressView">
        <!-- format="color" 颜色 -->
        <attr name="ringColor" format="color" />
        <!-- format="dimension" 尺寸 -->
        <attr name="ringWidth" format="dimension" />
        <!-- enum 枚举 -->
        <attr name="ringStyle">
            <enum name="STROKE" value="0" />
            <enum name="FILL"   value="1" />
        </attr>
        <!-- flag 标记位 -->
        <attr name="showText">
            <flag name="none" value="0" />
            <flag name="percent" value="1" />
            <flag name="remain" value="2" />
        </attr>
        <!-- reference 引用 -->
        <attr name="labelDrawable" format="reference" />
        <!-- 布尔 -->
        <attr name="indeterminate" format="boolean" />
        <!-- 整数 -->
        <attr name="maxCount" format="integer" />
        <!-- 浮点 -->
        <attr name="startAngle" format="float" />
    </declare-styleable>
</resources>
```

**读取属性**：

```java
private void init(Context context, AttributeSet attrs, int defStyleAttr) {
    // 1. 读取 styleable 中定义的属性
    TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.RingProgressView, defStyleAttr, 0);
    try {
        ringColor = a.getColor(R.styleable.RingProgressView_ringColor, Color.BLUE);
        ringWidth = a.getDimension(R.styleable.RingProgressView_ringWidth, dp(8));
        ringStyle = a.getInt(R.styleable.RingProgressView_ringStyle, 0);
        showText = a.getInt(R.styleable.RingProgressView_showText, 1);
        labelDrawable = a.getDrawable(R.styleable.RingProgressView_labelDrawable);
        indeterminate = a.getBoolean(R.styleable.RingProgressView_indeterminate, false);
        maxCount = a.getInt(R.styleable.RingProgressView_maxCount, 100);
        startAngle = a.getFloat(R.styleable.RingProgressView_startAngle, -90f);
    } finally {
        a.recycle();      // 必须 recycle，否则泄漏 TypedArray
    }
}
```

**`obtainStyledAttributes` 的四参数重载**（按优先级从低到高）：

```text
obtainStyledAttributes(context, attrs, R.styleable.X)                    // 只查 attrs.xml
obtainStyledAttributes(context, attrs, R.styleable.X, defStyleAttr)       // 再查 defStyleAttr 指向的主题 style
obtainStyledAttributes(context, attrs, R.styleable.X, defStyleAttr, defStyleRes)  // 最后查 defStyleRes
```

> 面试点：**为什么需要 `defStyleAttr`？** 因为它允许控件的默认值来自当前主题（`?attr/`），实现"控件跟随主题换肤"，这是 Material 组件（`MaterialButton` 等）实现动态换肤的核心机制。

**在 XML 中使用**：

```xml
<com.example.RingProgressView
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    app:ringColor="@color/brand_primary"
    app:ringWidth="12dp"
    app:ringStyle="FILL"
    app:showText="percent|remain"
    app:indeterminate="true"
    app:maxCount="200" />
```

> `xmlns:app` 是自定义属性命名空间；`res-auto` 表示"从当前应用包名自动解析"。

### 4. Canvas 与 Paint 核心 API

```text
Canvas（画布）        Paint（画笔）
 ├─ drawLine          ├─ 颜色 color / shader
 ├─ drawRect          ├─ 样式 style(FILL/STROKE/FILL_AND_STROKE)
 ├─ drawCircle        ├─ 描边宽度 strokeWidth / 描边帽 strokeCap
 ├─ drawArc           ├─ 抗锯齿 isAntiAlias
 ├─ drawPath          ├─ 字体 typeface / textSize / textAlign
 ├─ drawText          └─ 阴影 setShadowLayer
 ├─ drawBitmap
 ├─ drawPicture
 └─ save / restore / clipRect / rotate / scale / translate
```

**Paint 的三大能力**：

```java
// 1. 颜色
paint.setColor(Color.RED);
paint.setAlpha(128);
paint.setColorFilter(new PorterDuffColorFilter(Color.RED, PorterDuff.Mode.SRC_IN));

// 2. Shader（着色器）——渐变、位图、线性渐变
paint.setShader(new LinearGradient(
        0, 0, 200, 0,
        new int[]{Color.RED, Color.BLUE},
        null, Shader.TileMode.CLAMP));

// 3. 字体
paint.setTextSize(dp(16));
paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
paint.setTextAlign(Paint.Align.CENTER);
```

**Shader 的四种常见类型**：

| Shader | 用途 |
| --- | --- |
| `LinearGradient` | 线性渐变 |
| `RadialGradient` | 径向渐变（光晕） |
| `SweepGradient` | 扫描渐变（雷达图、CD 环形进度） |
| `BitmapShader` | 用图片做填充（马赛克、水印） |

**Path 的常用操作**：

```java
Path path = new Path();
path.moveTo(0, 0);
path.lineTo(100, 0);
path.cubicTo(150, 0, 150, 100, 100, 100);   // 贝塞尔曲线
path.close();
path.addRoundRect(new RectF(...), 8f, 8f, Path.Direction.CW);
path.arcTo(...);

// PathMeasure：按弧长取点，做路径动画
PathMeasure pm = new PathMeasure(path, false);
float len = pm.getLength();
float[] pos = new float[2];
pm.getPosTan(len * fraction, pos, null);
```

**PorterDuff 与 Xfermode**（做遮罩、橡皮擦、图像合成）：

```java
// 旧 API（API 29 已废弃）
paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));

// 新 API
paint.setXfermode(new BlendModeXfermode(BlendMode.DST_IN));
```

| 模式 | 效果 |
| --- | --- |
| `SRC` | 只显示新绘制的（盖住旧的） |
| `DST` | 只显示原来的 |
| `SRC_IN` | 新图只保留在旧图范围内的部分（**做遮罩文字**） |
| `DST_IN` | 旧图只保留在新图范围内的部分（**橡皮擦**） |
| `CLEAR` | 清除（变透明） |
| `ADD` / `MULTIPLY` | 相加 / 正片叠底（**发光、阴影**） |

### 5. 硬件加速与 invalidate

```text
onDraw(Canvas) 的 Canvas 在硬件加速下是 RecordingCanvas：
   绘制命令 ──► 录制进 DisplayList ──► RenderThread 执行 ──► GPU
```

| 场景 | 硬件加速 | 软件渲染 |
| --- | --- | --- |
| 普通 `drawLine/drawText` | ✅ 快 | 可用 |
| `canvas.getMatrix()` / `getTextBounds()` | ❌ 抛异常 | ✅ |
| `clipPath` 抗锯齿 | 不支持（无 AA） | 支持 |
| `saveLayer` 多次离屏 | 开销大 | 可控 |
| View 截图 `view.draw(canvas)` | 只得到 DisplayList | 得到真实像素 |
| 大量 `Path` + `MaskFilter` | 慢 | 更慢 |

**`invalidate()` vs `postInvalidate()`**：

| 方法 | 线程 | 用途 |
| --- | --- | --- |
| `invalidate()` | 主线程 | 在主线程回调中刷新 |
| `postInvalidate()` | 任意线程 | 在子线程中刷新（内部 `postInvalidateOnAnimation` → `Handler.post` → `invalidate`） |
| `postInvalidateOnAnimation()` | 主线程 | 下一帧刷新（配合动画最佳，避免同帧重复刷新） |

> 面试点：**动画中应该用 `postInvalidateOnAnimation()`**，它会与 VSync 对齐，一帧只刷新一次；用 `invalidate()` 可能在一帧内触发多次重绘。

### 6. 性能红线

| 反模式 | 后果 | 正确做法 |
| --- | --- | --- |
| 在 `onDraw` 里 `new Paint()` / `new RectF()` / `new String()` | 每帧分配 → GC 抖动 → 掉帧 | 提到字段中预创建，或在 `onSizeChanged` 中创建 |
| 在 `onDraw` 里做复杂计算（排序、大量字符串拼接） | 主线程耗时 | 预计算，缓存结果 |
| 在 `onDraw` 里频繁 `invalidate()` | 同帧多次绘制 | 用 `postInvalidateOnAnimation()` |
| 频繁 `requestLayout()` | 整棵子树重新测量 | 内容变化用 `invalidate()`；确需改尺寸才 `requestLayout()` |
| 用 `String.valueOf()` / `String.format()` 每帧生成文本 | 分配 + 慢 | 预格式化或缓存 |
| `setLayerType(LAYER_TYPE_SOFTWARE)` | 关闭硬件加速，绘制变慢 | 只在必要时局部使用 |
| `onTouchEvent` 里做耗时操作 | 阻塞输入 | 交给子线程 |

**标准写法：把与尺寸相关的计算放在 `onSizeChanged`**：

```java
private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
private final RectF oval = new RectF();
private float centerX, centerY, radius;

@Override
protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    centerX = w / 2f;
    centerY = h / 2f;
    radius = Math.min(w, h) / 2f - paint.getStrokeWidth();
    oval.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
}
```

### 7. `setWillNotDraw(false)` 的作用

`ViewGroup` 默认 `mPrivateFlags` 中带 `PFLAG_SKIP_DRAW`（即 `willNotDraw = true`），含义是"**我不会自己画任何东西，跳过 onDraw**"。这是对 `ViewGroup` 的优化，因为绝大多数容器只是摆放子 View。

```java
public class MyContainer extends ViewGroup {
    public MyContainer(Context context) {
        super(context);
        setWillNotDraw(false);   // 告诉父类：我要在 dispatchDraw 前后画东西
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);   // 先画子 View
        canvas.drawCircle(...);        // 再画自己的内容
    }
}
```

| 方法 | 时机 | 用途 |
| --- | --- | --- |
| `onDraw(canvas)` | 子 View **之前** | 画在子 View **下面**（背景层） |
| `dispatchDraw(canvas)` | 子 View **之后** | 画在子 View **上面**（前景层） |

> 面试点：**`ViewGroup` 的 `onDraw` 默认不会被调用**，所以自定义容器要画东西必须 `setWillNotDraw(false)`；而且在 `onDraw` 里画的内容会被子 View **遮住**，要画在上面应该用 `dispatchDraw` 或 `dispatchDraw` 之后。

### 8. 状态保存与恢复

```java
@Override
protected Parcelable onSaveInstanceState() {
    Bundle bundle = new Bundle();
    bundle.putParcelable("super", super.onSaveInstanceState());   // 必须保存 super
    bundle.putFloat("progress", progress);
    bundle.putInt("mode", mode);
    return bundle;
}

@Override
protected void onRestoreInstanceState(Parcelable state) {
    if (state instanceof Bundle) {
        Bundle bundle = (Bundle) state;
        super.onRestoreInstanceState(bundle.getParcelable("super"));
        progress = bundle.getFloat("progress");
        mode = bundle.getInt("mode");
        invalidate();
    } else {
        super.onRestoreInstanceState(state);
    }
}
```

> 更简单的做法：实现 `onSaveInstanceState(Bundle)` / `onRestoreInstanceState(Bundle)` 变体重载（API 21+），可以省去 Parcelable 的手工包装。

## 代码示例

### 1. 完整的自定义绘制型 View（带自定义属性）

```java
public class RingProgressView extends View {

    // 1. 把 Paint / Path 提为字段，避免 onDraw 中分配
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    // 2. 业务状态
    private float progress = 0f;
    private int maxCount = 100;
    private boolean showText = true;
    private float startAngle = -90f;

    public RingProgressView(Context context) { this(context, null, 0); }

    public RingProgressView(Context context, AttributeSet attrs) { this(context, attrs, 0); }

    public RingProgressView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs, defStyleAttr);
    }

    private void init(Context context, AttributeSet attrs, int defStyleAttr) {
        // 读取自定义属性，defStyleAttr 让默认值可以来自主题
        TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.RingProgressView, defStyleAttr, 0);
        try {
            int ringColor = a.getColor(R.styleable.RingProgressView_ringColor, 0xFF6200EE);
            float ringWidth = a.getDimension(R.styleable.RingProgressView_ringWidth, dp(10));
            maxCount = a.getInt(R.styleable.RingProgressView_maxCount, 100);
            showText = a.getBoolean(R.styleable.RingProgressView_showText, true);
            startAngle = a.getFloat(R.styleable.RingProgressView_startAngle, -90f);
            int style = a.getInt(R.styleable.RingProgressView_ringStyle, 0);

            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(ringWidth);
            ringPaint.setColor(ringColor);
            ringPaint.setStrokeCap(Paint.Cap.ROUND);

            progressPaint.setStyle(style == 1 ? Paint.Style.FILL : Paint.Style.STROKE);
            progressPaint.setStrokeWidth(ringWidth);
            progressPaint.setColor(0xFF03DAC5);
            progressPaint.setStrokeCap(Paint.Cap.ROUND);

            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(dp(20));
            textPaint.setColor(0xFF212121);
        } finally {
            a.recycle();
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** 对外 API：内容变化 → 只重绘 */
    public void setProgress(float progress) {
        this.progress = Math.max(0f, Math.min(1f, progress));
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desired = (int) dp(120);
        int w = resolveSize(desired, widthMeasureSpec);
        int h = resolveSize(desired, heightMeasureSpec);
        int size = Math.min(w, h);        // 保证是正圆
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float radius = Math.min(w, h) / 2f - ringPaint.getStrokeWidth();
        float cx = w / 2f, cy = h / 2f;
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);   // 让背景 drawable 被绘制
        float cx = getWidth() / 2f, cy = getHeight() / 2f;

        canvas.drawArc(arcRect, 0, 360f, false, ringPaint);                    // 底圈
        canvas.drawArc(arcRect, startAngle, 360f * progress, false, progressPaint); // 进度

        if (showText) {
            String text = Math.round(progress * maxCount) + "/" + maxCount;
            // 用 FontMetrics 精确垂直居中，避免每次 measure
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float baseline = cy - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(text, cx, baseline, textPaint);
        }
    }

    @Override
    protected void onSaveInstanceState() {
        Bundle bundle = new Bundle();
        bundle.putParcelable("super", super.onSaveInstanceState());
        bundle.putFloat("progress", progress);
        super.onSaveInstanceState(bundle);
    }

    @Override
    protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
        progress = state.getFloat("progress");
    }
}
```

### 2. 自定义交互型 View：可拖动的滑块

```java
public class SeekBarView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float progress = 0.5f;
    private float thumbRadius;
    private int trackStart, trackEnd;
    private boolean dragging = false;
    private OnProgressChangedListener listener;

    public interface OnProgressChangedListener {
        void onProgressChanged(float progress, boolean fromUser);
    }

    public SeekBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        trackPaint.setColor(0xFFE0E0E0);
        trackPaint.setStrokeWidth(dp(4));
        thumbPaint.setColor(0xFF6200EE);
        setClickable(true);       // 关键：默认 onTouchEvent 才能消费事件
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    public void setOnProgressChangedListener(OnProgressChangedListener l) { listener = l; }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = (int) dp(48);
        int w = resolveSize((int) dp(200), widthMeasureSpec);
        int h = resolveSize(height, heightMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        thumbRadius = dp(10);
        trackStart = (int) dp(16);
        trackEnd = w - (int) dp(16);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cy = getHeight() / 2f;
        float thumbX = trackStart + (trackEnd - trackStart) * progress;

        canvas.drawLine(trackStart, cy, trackEnd, cy, trackPaint);
        canvas.drawCircle(thumbX, cy, thumbRadius, thumbPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);  // 抢下整串手势
                updateProgress(event.getX(), true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    updateProgress(event.getX(), true);
                }
                return true;
            case MotionEvent.ACTION_UP:
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:      // 必须处理，取消按压状态
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void updateProgress(float x, boolean fromUser) {
        float p = (x - trackStart) / (trackEnd - trackStart);
        p = Math.max(0f, Math.min(1f, p));
        if (p != progress) {
            progress = p;
            invalidate();
            if (listener != null) listener.onProgressChanged(p, fromUser);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
```

### 3. 自定义 ViewGroup：手写流式布局

```java
public class FlowLayout extends ViewGroup {

    private int itemHorizontalSpacing = dp(8);
    private int itemVerticalSpacing = dp(8);

    public FlowLayout(Context context) { super(context); }

    public FlowLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.FlowLayout);
        try {
            itemHorizontalSpacing = a.getDimensionPixelSize(R.styleable.FlowLayout_itemHorizontalSpacing, dp(8));
            itemVerticalSpacing = a.getDimensionPixelSize(R.styleable.FlowLayout_itemVerticalSpacing, dp(8));
        } finally {
            a.recycle();
        }
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    /** 测量：先问孩子要多大，再决定换行位置 */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        final int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        final int availableWidth = widthSize - getPaddingLeft() - getPaddingRight();

        int lineWidth = 0;      // 当前行的总宽
        int lineHeight = 0;     // 当前行的最大高
        int totalHeight = 0;    // 所有行的总高

        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;

            // 用 measureChildWithMargins 让每个孩子拿到正确的 Spec
            measureChildWithMargins(child, 0, 0, widthMeasureSpec, 0);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            final int childWidth = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            final int childHeight = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;

            if (lineWidth + childWidth > availableWidth && lineWidth > 0) {
                // 需要换行：先把当前行的高度累加，重新开始一行
                totalHeight += lineHeight + itemVerticalSpacing;
                lineWidth = childWidth;
                lineHeight = childHeight;
            } else {
                lineWidth += childWidth + itemHorizontalSpacing;
                lineHeight = Math.max(lineHeight, childHeight);
            }
        }
        totalHeight += lineHeight;   // 最后一行

        int desiredWidth = lineWidth;
        int desiredHeight = totalHeight + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(
                resolveSize(desiredWidth + getPaddingLeft() + getPaddingRight(), widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec));
    }

    /** 布局：把测量好的孩子摆到具体坐标 */
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final int availableWidth = r - l - getPaddingLeft() - getPaddingRight();
        int x = getPaddingLeft();
        int y = getPaddingTop();
        int lineHeight = 0;

        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();

            if (x + child.getMeasuredWidth() > l + getPaddingLeft() + availableWidth && x > getPaddingLeft()) {
                x = getPaddingLeft();
                y += lineHeight + itemVerticalSpacing;
                lineHeight = 0;
            }
            child.layout(x + lp.leftMargin, y + lp.topMargin,
                         x + lp.leftMargin + child.getMeasuredWidth(),
                         y + lp.topMargin + child.getMeasuredHeight());
            x += child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin + itemHorizontalSpacing;
            lineHeight = Math.max(lineHeight, child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin);
        }
    }

    // ViewGroup 子类必须提供 LayoutParams 支持
    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new MarginLayoutParams(getContext(), attrs);
    }
    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }
    @Override
    protected boolean checkLayoutParams(LayoutParams p) {
        return p instanceof MarginLayoutParams;
    }
}
```

### 4. 自定义属性的 attrs.xml 完整示例

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <declare-styleable name="RingProgressView">
        <attr name="ringColor" format="color" />
        <attr name="ringWidth" format="dimension" />
        <attr name="maxCount" format="integer" />
        <attr name="showText" format="boolean" />
        <attr name="startAngle" format="float" />
        <attr name="ringStyle">
            <enum name="STROKE" value="0" />
            <enum name="FILL" value="1" />
        </attr>
    </declare-styleable>

    <declare-styleable name="FlowLayout">
        <attr name="itemHorizontalSpacing" format="dimension" />
        <attr name="itemVerticalSpacing" format="dimension" />
    </declare-styleable>
</resources>
```

## 常见问题 / 面试要点

**读者问：自定义 View 的 `onDraw` 为什么会频繁执行？怎么优化？**

**AI答：**

1. **`onDraw` 被调用的时机**：
   - 首次布局后绘制第一帧；
   - 任何调用 `invalidate()` 的地方（内容、颜色、位置变化）；
   - 属性动画每一帧（动画改的是属性，属性变化会触发 `invalidate`）；
   - 父容器 `requestLayout()` 导致重新布局后的绘制；
   - 硬件加速 DisplayList 需要重建时。
2. **典型的高频来源**：
   - **动画**：`ObjectAnimator` 每帧 `invalidate`，这是正常的；
   - **误用 `requestLayout()`**：内容变化时错误地调用 `requestLayout`，会触发整棵子树重新测量 + 绘制，成本远高于 `invalidate`；
   - **在 `onDraw` 里修改属性**：如果 `onDraw` 里改了会影响绘制的东西又调 `invalidate`，就形成"绘制 → invalidate → 再绘制"的无限循环，**每帧都画，CPU 100%**。
   - **频繁 `postInvalidate()`** 而不用 `postInvalidateOnAnimation()`。
3. **优化手段**：
   - 内容变化用 `invalidate()`，只有尺寸变化才 `requestLayout()`；
   - 动画用 `postInvalidateOnAnimation()`，与 VSync 对齐，一帧只画一次；
   - **`onDraw` 里不 `new` 对象**（Paint、Path、RectF 提前创建）；
   - 复杂计算（路径生成、排序）提前算好并缓存，用 `invalidate()` 触发重画；
   - 大图/复杂绘制用 `Bitmap` 缓存，或用 `Picture`/`RenderNode` 缓存；
   - 用 `Choreographer` 或 `FrameMetrics` 统计实际绘制耗时；
   - 局部刷新：API 26+ 用 `invalidate(int l, int t, int r, int b)` 只重画脏矩形。
4. **面试话术**："**`onDraw` 频繁执行不一定是 bug（动画就是），但如果在没有动画时它一直在执行，那就是在 `onDraw` 里改状态又 `invalidate`，形成了自激循环。**"

---

**读者问：`setWillNotDraw(false)` 是什么意思？什么时候需要调用？**

**AI答：**

1. **含义**：默认情况下 `ViewGroup` 的 `willNotDraw = true`（内部标记 `PFLAG_SKIP_DRAW`），意思是"**我不会绘制任何内容，可以跳过 `onDraw`**"。这是性能优化——绝大多数容器只是摆放子 View，不需要绘制。
2. **何时需要调用**：当自定义 `ViewGroup` 需要**自己绘制内容**（背景装饰、连线、蒙层、边框）时。
3. **绘制时机的选择**：
   | 回调 | 时机 | 效果 |
   | --- | --- | --- |
   | `onDraw` | **子 View 之前** | 内容在子 View **下面**，会被子 View 遮挡 |
   | `dispatchDraw` | 子 View 之后 | 内容在子 View **上面** |
   | `onForeground` | 全部之后 | 前景层（Material 的 ripple 前景） |
4. **正确写法**：
   ```java
   public MyContainer(Context context) {
       super(context);
       setWillNotDraw(false);   // 必须调用，否则 onDraw 永远不会被调
   }

   @Override
   protected void onDraw(Canvas canvas) {
       super.onDraw(canvas);
       canvas.drawCircle(50, 50, 20, paint);   // 画在子 View 之下
   }
   ```
5. **注意**：只有 `ViewGroup` 需要；`View` 默认 `willNotDraw = false`，`onDraw` 正常会被调用。
6. **面试话术**："**`willNotDraw` 是 ViewGroup 的性能开关。忘了 `setWillNotDraw(false)`，自定义容器的 `onDraw` 就是死代码，而且 Debug 时完全没报错，最难查。**"

---

**读者问：自定义 View 怎么支持在 XML 中配置属性？为什么 `obtainStyledAttributes` 要传 `defStyleAttr`？**

**AI答：**

1. **三步走**：
   - **第一步**：在 `res/values/attrs.xml` 中用 `<declare-styleable>` 声明属性（名字必须以自定义 View 类名为前缀，避免冲突）。
   - **第二步**：在构造函数中 `context.obtainStyledAttributes(attrs, R.styleable.XXX, defStyleAttr, 0)` 读取。
   - **第三步**：XML 中用 `xmlns:app="http://schemas.android.com/apk/res-auto"` 命名空间赋值。
2. **`defStyleAttr` 的作用**：它指向一个**主题属性**（形如 `R.attr.materialButtonStyle`），作用是当 XML 中没有显式设置某属性时，**从当前主题的该 style 中取默认值**。这实现了"控件跟随 App 主题动态换肤"——换 Theme 就能改控件外观，而不需要改 XML。
3. **四参数重载的优先级**（低 → 高）：
   ```text
   defStyleRes  <  defStyleAttr 指向的 style  <  XML 中显式设置的值
   ```
   更准确地说：`obtainStyledAttributes` 会依次在 `attrs`（XML）、`defStyleAttr`（主题属性）、`defStyleRes`（默认资源）中查找，找到即止。
4. **常见坑**：
   - 忘记 `a.recycle()` → TypedArray 泄漏；
   - `R.styleable.XXX` 名字写错 → 编译不过；
   - 自定义属性名太通用（如 `color`）→ 与系统属性冲突，**必须加前缀**；
   - 只重载 2 参构造器 → 主题换肤能力丢失。
5. **面试话术**："**`defStyleAttr` 是控件接入主题体系的关键——Material 组件之所以能一键换肤，就是因为它们的默认样式来自 `?attr/xxxStyle`。**"

---

**读者问：自定义 View 的点击事件没反应，怎么排查？**

**AI答：**

按优先级排查：

1. **没有 `setClickable(true)`**（90% 的原因）。`View.onTouchEvent` 默认只在 `clickable || longClickable` 时返回 true；自定义 View 默认两者都是 false → 事件不被消费。
2. **重写 `onTouchEvent` 时 DOWN 分支没返回 true** → 收不到后续 MOVE/UP。
3. **没有调用 `performClick()`** → `OnClickListener` 不触发，且 TalkBack 等无障碍服务读不到点击事件。
4. **父容器拦截了事件** → 检查父 `onInterceptTouchEvent` 是否误返回 true；可用 `getParent().requestDisallowInterceptTouchEvent(true)` 抢下手势。
5. **View 被 `setEnabled(false)` / `GONE` / `INVISIBLE`**。
6. **触摸热区太小** → 视觉上看不见但点不到；用 `TouchDelegate` 扩大。
7. **代码检查清单**：
   ```java
   setClickable(true);
   setFocusable(true);
   @Override public boolean onTouchEvent(MotionEvent e) {
       switch (e.getActionMasked()) {
           case MotionEvent.ACTION_DOWN: setPressed(true);  invalidate(); return true;
           case MotionEvent.ACTION_UP:   setPressed(false); invalidate(); performClick(); return true;
           case MotionEvent.ACTION_CANCEL: setPressed(false); invalidate(); return true;
       }
       return super.onTouchEvent(e);
   }
   ```
8. **面试话术**："**自定义 View 点击失效，第一反应是 `setClickable(true)`，第二反应是 DOWN 分支返回 true，第三反应是 `performClick()` 没调。**"

---

**读者问：如何在自定义 View 中做状态保存？旋转屏幕后为什么进度条归零了？**

**AI答：**

1. **为什么归零**：View 状态的保存/恢复走 `onSaveInstanceState()` → `Parcelable`，由 `ViewRootImpl` 在 `saveHierarchyState` 时统一收集。**自定义 View 的额外状态必须自己实现**，系统不知道你的 `progress` 字段存在。
2. **两种写法**：
   - **Parcelable 方式**（传统）：
     ```java
     @Override protected Parcelable onSaveInstanceState() {
         Bundle b = new Bundle();
         b.putParcelable("super", super.onSaveInstanceState());
         b.putFloat("progress", progress);
         return b;
     }
     @Override protected void onRestoreInstanceState(Parcelable state) {
         if (state instanceof Bundle) {
             Bundle b = (Bundle) state;
             super.onRestoreInstanceState(b.getParcelable("super"));
             progress = b.getFloat("progress");
         } else {
             super.onRestoreInstanceState(state);
         }
     }
     ```
   - **Bundle 变体**（API 21+，更简洁）：
     ```java
     @Override protected void onSaveInstanceState(Bundle outState) {
         super.onSaveInstanceState(outState);
         outState.putFloat("progress", progress);
     }
     @Override protected void onRestoreInstanceState(Bundle savedInstanceState) {
         super.onRestoreInstanceState(savedInstanceState);
         progress = savedInstanceState.getFloat("progress");
     }
     ```
3. **注意 `setId()`**：View 必须有非 `NO_ID` 的 id，否则 `saveHierarchyState` 不会保存它（系统按 id 存状态）。自定义 View 在 XML 中要写 `android:id`。
4. **替代方案**：把状态放到 `ViewModel`（详见 [[../JetpackCompose/状态管理]] 中 `rememberSaveable` 的定位），UI 层只渲染状态，天然免疫配置变更。
5. **面试话术**："**View 的状态保存是系统代劳的，但只覆盖系统认识的字段；自定义字段必须自己存。而且没设 id 的 View 根本不参与保存。**"

---

**读者问：`Canvas` / `Paint` / `Path` / `Shader` 分别是什么？Shader 常见的几种类型？**

**AI答：**

1. **`Canvas`**：画布，提供绘制命令（`drawLine` / `drawText` / `drawPath` / `drawBitmap`）。在硬件加速下它是**指令录制器**，命令最终由 RenderThread 提交 GPU 执行。
2. **`Paint`**：画笔，描述"怎么画"：颜色、样式（`FILL`/`STROKE`）、描边宽度、抗锯齿、字体、Shader、阴影。
3. **`Path`**：路径，由直线段、二次/三次贝塞尔曲线、弧线组成，用于复杂图形；配合 `PathMeasure` 可做沿路径动画。
4. **`Shader`**：着色器，把颜色计算从"单一填充色"升级为"按位置/图案填充"：
   - `LinearGradient`（线性渐变）
   - `RadialGradient`（径向渐变、光晕）
   - `SweepGradient`（扫描渐变，环形进度、雷达图）
   - `BitmapShader`（图片平铺、马赛克、水印）
   - `ComposeShader`（两个 Shader 组合，如**文字水印**：`BitmapShader` + `PorterDuff.Mode.DST_IN`）
5. **经典案例：绘制水印文字**：
   ```java
   Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
   Canvas c = new Canvas(bmp);
   Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
   p.setTextSize(dp(40));
   p.setColor(0x30FF0000);
   c.drawText("Hello", 100, 100, p);
   // 把 bmp 作为 BitmapShader 铺满，并用 SRC_IN 只保留文字形状
   paint.setShader(new BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
   paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
   ```
6. **面试话术**：**"Paint 是画笔，Canvas 是纸，Path 是草稿，Shader 是调色方案。"**

---

**读者问：自定义 ViewGroup 需要重写哪些方法？为什么不加会崩？**

**AI答：**

1. **必须重写的方法**：
   | 方法 | 不重写的后果 |
   | --- | --- |
   | `onMeasure` | 子 View 尺寸为 0，**不显示**（不是崩溃，但看不见） |
   | `onLayout` | 子 View 不会被摆放，**全在 (0,0)** 位置重叠；ViewGroup 默认实现是空方法 |
   | `generateLayoutParams(AttributeSet)` | `LayoutInflater` 解析 XML 子项时抛 `InflateException: ClassCastException` |
   | `generateDefaultLayoutParams()` | 代码动态 `addView` 时抛异常 |
   | `checkLayoutParams(LayoutParams)` | `addView` 时类型不匹配抛异常 |
2. **为什么会崩**：Android 的 `LayoutInflater` 在解析 `<子View>` 标签时，会调用父容器的 `generateLayoutParams(attrs)` 来创建 `LayoutParams`。ViewGroup 的默认实现返回 `null`，随后强转成 `LinearLayout.LayoutParams` 时抛 `ClassCastException`。
3. **标准模板**：
   ```java
   @Override public LayoutParams generateLayoutParams(AttributeSet attrs) {
       return new MarginLayoutParams(getContext(), attrs);
   }
   @Override protected LayoutParams generateDefaultLayoutParams() {
       return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
   }
   @Override protected boolean checkLayoutParams(LayoutParams p) {
       return p instanceof MarginLayoutParams;
   }
   ```
4. **常用内置 LayoutParams**：`MarginLayoutParams`（支持 margin）、`FrameLayout.LayoutParams`（gravity）、`LinearLayout.LayoutParams`（weight）、`RelativeLayout.LayoutParams`。
5. **面试话术**："**自定义 ViewGroup 的四件套：`onMeasure` + `onLayout` + 三个 `LayoutParams` 方法，缺一不可。**"

## 关联笔记

- [[绘制流程]]：`measure` / `layout` / `draw` 三个回调的完整契约
- [[事件分发机制]]：`onTouchEvent` 的返回值语义与 CANCEL 处理
- [[../动画/属性动画]]：属性动画如何驱动自定义 View 的 `invalidate`
- [[../布局与主题/ConstraintLayout]]：用约束替代手写 `onLayout`
- [[../布局与主题/主题与样式]]：`?attr/` 自定义属性与主题系统
- [[../JetpackCompose/声明式UI与重组]]：Compose 中"不需要自定义 View"的替代方案
- [[../README]]：第 3 部分总览

## 参考资料

- Google 官方文档 "Custom Views"：https://developer.android.com/develop/ui/views/architecture/custom-views
- Google 官方文档 "Defining Custom Attributes"：https://developer.android.com/develop/ui/views/structure-and-styles
- AOSP 源码：`android/view/View.java`、`android/view/ViewGroup.java`、`android/graphics/Canvas.java`
- 《Android 高级编程》第 4 版 · 第 8 章 "自定义 View"
- 郭霖《Android 开发精讲》第 5 章
