# KNcloud 网站端授权登录与 App 唤起对接文档

本文档用于指导 KNcloud 官网前端（如 V2Board / Xboard 或自定义 Web 面板）对接 **Android 客户端一键授权登录**。

---

## 📌 一、URL Scheme 唤起协议规范

Android 客户端已注册并监听以下 URL Scheme：
- `kncloud://login`
- `top.kncloud.com://login`
- `kncloud://auth`

### 完整 URL 格式
```text
kncloud://login?token={TOKEN}&email={EMAIL}&domain={DOMAIN}&sub_url={SUB_URL}
```

### 参数说明

| 参数名 | 必填 | 说明 | 示例 |
| :--- | :--- | :--- | :--- |
| `token` | **是** | 用户认证 Token（V2Board/Xboard 登录接口返回的 `auth_data` 或 `token`） | `eyJhbGciOi...` |
| `email` | 否 | 用户邮箱账号（用于客户端显示与自动填充） | `user@example.com` |
| `domain` | 否 | 网站 API 域名（若不填则默认使用 App 内置域名） | `https://www.kncloud.top` |
| `sub_url`| 否 | 用户的直接订阅链接（若不填，App 会自动调用 `/api/v1/user/getSubscribe` 获取） | `https://www.kncloud.top/api/v1/client/subscribe?token=...` |

> ⚠️ **注意**：所有参数值均需经过 `encodeURIComponent` 进行 URL 编码。

---

## 🚀 二、网站前端对接实现

前端有两种常见的对接场景，可根据实际需求选择其一或两者结合：

### 方案 A：在用户中心增加「一键登录 / 授权到 KNcloud 客户端」按钮（推荐）

在用户的控制台（Dashboard / 用户中心 / 快速开始页面）放置一个按钮，用户在手机浏览器登录后点击即可直达 App。

#### 1. Vue / React / 原生 JS 通用实现函数
```javascript
/**
 * 唤起并授权登录 KNcloud Android 客户端
 * @param {string} token - 用户 auth_data 或 token
 * @param {string} email - 用户邮箱
 * @param {string} [subUrl] - (可选) 用户订阅链接
 */
function openKNcloudApp(token, email, subUrl = '') {
  if (!token) {
    alert('请先登录后再进行授权');
    return;
  }

  const domain = window.location.origin;
  const scheme = `kncloud://login?token=${encodeURIComponent(token)}&email=${encodeURIComponent(email || '')}&domain=${encodeURIComponent(domain)}${subUrl ? '&sub_url=' + encodeURIComponent(subUrl) : ''}`;

  // 尝试唤起 App
  window.location.href = scheme;
}
```

#### 2. Vue 2 / Vue 3 组件模板示例
```html
<template>
  <div class="auth-box">
    <button class="btn-kncloud-auth" @click="handleAuthToApp">
      <i class="icon-android"></i> 一键授权登录 KNcloud 客户端
    </button>
  </div>
</template>

<script>
export default {
  methods: {
    handleAuthToApp() {
      // 从 localStorage / Vuex / Pinia 获取当前登录用户信息
      const token = localStorage.getItem('auth_data') || localStorage.getItem('token');
      const email = this.$store?.state?.user?.email || localStorage.getItem('user_email') || '';
      const subUrl = this.$store?.state?.user?.subscribe_url || '';

      const domain = window.location.origin;
      const url = `kncloud://login?token=${encodeURIComponent(token)}&email=${encodeURIComponent(email)}&domain=${encodeURIComponent(domain)}${subUrl ? '&sub_url=' + encodeURIComponent(subUrl) : ''}`;

      window.location.href = url;
    }
  }
}
</script>
```

---

### 方案 B：登录成功后自动回跳唤起 App（全自动 OAuth 式）

如果用户是从 App 点击「官网授权登录」跳转到网页登录的，登录成功后可直接自动唤起 App。

#### 实现逻辑：
在登录请求成功的回调函数中增加唤起判断：

```javascript
// 示例：用户在登录页提交表单成功后
async function onLoginSuccess(response) {
  const token = response.data.auth_data || response.data.token;
  const email = this.form.email;
  const domain = window.location.origin;

  // 1. 保存登录状态到网站本地（正常业务逻辑）
  localStorage.setItem('auth_data', token);

  // 2. 判断是否需要唤起 App（例如判断 URL query 参数或直接在移动端尝试唤起）
  const isFromApp = new URLSearchParams(window.location.search).get('from') === 'app' 
                 || window.location.hash.includes('from=app');

  // 如果是从 App 唤起打开的登录页，或者希望在手机端直接提示唤起：
  if (isFromApp || /Android/i.test(navigator.userAgent)) {
    const scheme = `kncloud://login?token=${encodeURIComponent(token)}&email=${encodeURIComponent(email)}&domain=${encodeURIComponent(domain)}`;
    window.location.href = scheme;
    return;
  }

  // 正常跳转到网站控制台
  this.$router.push('/dashboard');
}
```

---

## 🧪 三、测试与验证方法

### 1. 手机浏览器直接测试
在 Android 手机浏览器（Chrome / 夸克等）的地址栏中输入以下链接并访问（或创建一个简单的 HTML 页面并点击链接）：

```html
<a href="kncloud://login?token=test_token_123456&email=test@kncloud.top">测试唤起 KNcloud</a>
```
- **预期结果**：浏览器弹出“是否打开 KNcloud 应用”，点击确定后 App 启动并显示“正在获取并导入订阅节点…”。

### 2. 使用 ADB 命令行测试
通过 USB 连接手机或在模拟器中执行以下命令：

```bash
adb shell am start -W -a android.intent.action.VIEW -d "kncloud://login?token=YOUR_TEST_TOKEN&email=test@kncloud.top&domain=https://www.kncloud.top"
```

---

## ❓ 常见问题排查 (FAQ)

1. **Q：点击链接后手机浏览器提示“找不到应用程序”？**
   - A：请确保手机上已安装版本 **≥ v1.10.49** 的 KNcloud 客户端。

2. **Q：唤起 App 后提示“获取订阅失败”？**
   - A：请检查传入的 `token` 是否有效，以及 `domain` 是否可正常访问 `/api/v1/user/getSubscribe`。若后端返回了直接订阅地址，可一并传入 `&sub_url=...`。
