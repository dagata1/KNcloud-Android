# KNcloud 官网授权登录与 Android 客户端唤起对接完整文档

本文档为 **KNcloud 官网前端（V2Board / Xboard / 自定义 Web 平台）** 对接 **Android 客户端一键授权登录** 的完整技术指南。

---

## 📐 一、交互架构与流程

```text
┌────────────────────────┐                   ┌────────────────────────┐
│  KNcloud Android App   │                   │   手机外部浏览器 / 官网   │
└──────────┬─────────────┘                   └──────────┬─────────────┘
           │                                            │
           │ 1. 点击「官网授权登录」                       │
           │ ── 打开浏览器 (带 ?from=app_auth) ────────> │
           │                                            │
           │                                            │ 2. 检测到 from=app_auth
           │                                            │    - 若已登录：弹出授权确认 / 秒跳
           │                                            │    - 若未登录：展示登录表单
           │                                            │
           │                                            │ 3. 登录成功 / 确认授权
           │                                            │    - 执行 kncloud://login?token=...
           │ <── 唤起 App (Deep Link 传 Token) ───────── │
           │                                            │
           │ 4. 接收 Token、保存凭证、拉取节点并进主页       │
┌──────────┴─────────────┐                   ┌──────────┴─────────────┐
│  进入主页，节点同步完成   │                   │    授权完成 / 留在网页   │
└────────────────────────┘                   └────────────────────────┘
```

---

## 📌 二、URL Scheme 协议规范

### 1. 唤起协议地址
Android 客户端已注册并监听以下 Scheme：
- `kncloud://login` （推荐）
- `top.kncloud.com://login`
- `kncloud://auth`

### 2. 完整 URL 格式
```text
kncloud://login?token={TOKEN}&email={EMAIL}&domain={DOMAIN}&sub_url={SUB_URL}
```

### 3. 参数说明表

| 参数名 | 必填 | 类型 | 说明 | 示例 |
| :--- | :---: | :---: | :--- | :--- |
| `token` | **是** | String | 用户登录凭据（V2Board/Xboard 登录接口返回的 `auth_data` 或 `token`） | `eyJhbGciOi...` |
| `email` | 否 | String | 用户邮箱账号（用于客户端界面展示与自动填充） | `user@kncloud.top` |
| `domain` | 否 | String | 网站 API 域名（不填则默认使用 App 动态获取的 API 域名） | `https://www.kncloud.top` |
| `sub_url`| 否 | String | 订阅直连地址（不填时 App 会自动调用 `/api/v1/user/getSubscribe` 拉取） | `https://www.kncloud.top/api/v1/client/subscribe?token=...` |

> ⚠️ **编码注意**：所有参数值必须使用 `encodeURIComponent()` 进行转义。

---

## 💻 三、网站前端代码完整实现

前端只需在通用工具类或组件中引入以下逻辑即可覆盖全部场景：

### 1. 核心唤起工具函数 (`kncloudAuth.js`)

```javascript
/**
 * 唤起并授权登录 KNcloud 客户端
 * @param {string} token 用户 token / auth_data
 * @param {string} [email] 用户邮箱
 * @param {string} [subUrl] 可选订阅链接
 */
export function openKNcloudApp(token, email = '', subUrl = '') {
  if (!token) return;

  const domain = window.location.origin;
  const params = new URLSearchParams({
    token: token,
    email: email || '',
    domain: domain
  });

  if (subUrl) {
    params.append('sub_url', subUrl);
  }

  const schemeUrl = `kncloud://login?${params.toString()}`;

  // 触发浏览器唤起 App
  window.location.href = schemeUrl;
}

/**
 * 检查当前页面是否是由 KNcloud App 发起的授权请求
 */
export function isKNcloudAppAuthRequest() {
  const url = window.location.href;
  return url.includes('from=app_auth') || url.includes('app_auth=1');
}
```

---

### 2. 场景 A：登录页面（`Login.vue`）完整对接逻辑

兼容 **未登录用户输入账号密码后授权** 与 **已登录用户直接授权**，并给用户提供【授权打开 App】与【留在网页】的自主选择：

```javascript
<template>
  <div class="login-container">
    <!-- 原有登录表单 -->
    <form @submit.prevent="handleLogin">
      <input v-model="form.email" type="email" placeholder="邮箱" required />
      <input v-model="form.password" type="password" placeholder="密码" required />
      <button type="submit">登 录</button>
    </form>

    <!-- 授权弹窗（仅在从 App 跳转过来时根据需要显示） -->
    <div v-if="showAuthDialog" class="auth-modal">
      <div class="modal-content">
        <h3>应用授权提示</h3>
        <p>KNcloud 客户端申请授权登录您的账号：<b>{{ currentEmail }}</b></p>
        <div class="modal-actions">
          <button class="btn-primary" @click="confirmAuthToApp">授权并打开 App</button>
          <button class="btn-secondary" @click="cancelAuth">留在网页端</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { openKNcloudApp, isKNcloudAppAuthRequest } from '@/utils/kncloudAuth';

export default {
  data() {
    return {
      form: { email: '', password: '' },
      showAuthDialog: false,
      currentToken: '',
      currentEmail: ''
    };
  },
  mounted() {
    // 1. 处理【已登录用户】：如果用户已经处于登录状态且是从 App 跳转过来的
    const existingToken = localStorage.getItem('auth_data') || localStorage.getItem('token');
    const existingEmail = localStorage.getItem('user_email') || '';

    if (isKNcloudAppAuthRequest() && existingToken) {
      this.currentToken = existingToken;
      this.currentEmail = existingEmail;
      
      // 方式 1（极致流畅）：直接秒级唤起
      openKNcloudApp(existingToken, existingEmail);

      // 方式 2（弹窗确认）：如果想给用户选择权，开启下方弹窗
      // this.showAuthDialog = true;
    }
  },
  methods: {
    // 2. 处理【未登录用户】：输入账号密码登录成功后
    async handleLogin() {
      try {
        const res = await this.$api.login(this.form); // 网站原有登录接口
        const token = res.data.auth_data || res.data.token;
        const email = this.form.email;

        // 正常保存网站自身的登录态
        localStorage.setItem('auth_data', token);
        localStorage.setItem('user_email', email);

        // 如果不是从 App 过来的正常网页用户，正常进入控制台
        if (!isKNcloudAppAuthRequest()) {
          this.$router.push('/dashboard');
          return;
        }

        // 如果是从 App 跳转过来的授权请求，唤起 App
        openKNcloudApp(token, email);
      } catch (err) {
        console.error('登录失败', err);
      }
    },
    confirmAuthToApp() {
      openKNcloudApp(this.currentToken, this.currentEmail);
      this.showAuthDialog = false;
    },
    cancelAuth() {
      this.showAuthDialog = false;
      this.$router.push('/dashboard');
    }
  }
};
</script>
```

---

### 3. 场景 B：在用户中心/仪表盘增加「一键授权登录客户端」按钮

在用户的 Dashboard（控制台 / 快速开始）页面，提供一个直观的客户端授权入口：

```html
<!-- 控制台快捷操作区域 -->
<div class="quick-start-card">
  <h4>客户端快捷接入</h4>
  <button class="btn-auth-app" @click="authToKNcloud">
    <svg class="icon" viewBox="0 0 24 24"><!-- Android 图标 --></svg>
    一键授权登录 KNcloud Android 客户端
  </button>
</div>

<script>
import { openKNcloudApp } from '@/utils/kncloudAuth';

export default {
  methods: {
    authToKNcloud() {
      const token = localStorage.getItem('auth_data') || localStorage.getItem('token');
      const email = localStorage.getItem('user_email') || this.$store?.state?.user?.email || '';
      const subUrl = this.$store?.state?.user?.subscribe_url || '';

      if (!token) {
        this.$message.warning('请先登录');
        return;
      }

      openKNcloudApp(token, email, subUrl);
    }
  }
};
</script>
```

---

## 🧪 四、测试与调试方式

### 1. 手机浏览器直接访问测试
在 Android 手机自带浏览器或 Chrome 中，访问以下链接（可做成网页中的一个 `<a href="...">` 链接进行点击）：
```html
<a href="kncloud://login?token=test_token_sample&email=demo@kncloud.top&domain=https://www.kncloud.top">
  测试一键唤起 App 登录
</a>
```

### 2. 使用 ADB 命令测试
```bash
adb shell am start -W -a android.intent.action.VIEW \
  -d "kncloud://login?token=YOUR_TOKEN&email=demo@kncloud.top&domain=https://www.kncloud.top"
```

---

## 🛡️ 五、安全与设计说明

1. **普通用户无干扰**：只有链接包含 `from=app_auth` 才会执行 App 授权逻辑；普通 PC 或手机浏览器直接访问网站登录完全按照原流程进入控制台。
2. **凭据安全**：Scheme 唤起只在系统内部由 Android OS 路由至 `top.kncloud.com` 对应签名应用，不会向任何第三方泄露。
3. **多协议兼容**：客户端对 `kncloud://`、`top.kncloud.com://` 均已做全局拦截支持。
