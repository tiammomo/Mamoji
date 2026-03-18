# SSL/TLS 配置指南

## 1. 开发环境自签名证书

使用 `keytool` 生成 `PKCS12` 证书:

```bash
keytool -genkeypair \
  -alias mamoji \
  -keyalg RSA \
  -keysize 2048 \
  -storetype PKCS12 \
  -keystore mamoji.p12 \
  -validity 365 \
  -storepass changeit \
  -keypass changeit \
  -dname "CN=localhost, OU=Dev, O=Mamoji, L=Shanghai, ST=Shanghai, C=CN"
```

## 2. 导出 PEM（给 Nginx/Caddy 使用）

```bash
openssl pkcs12 -in mamoji.p12 -nokeys -out mamoji.crt
openssl pkcs12 -in mamoji.p12 -nocerts -out mamoji.key
```

## 3. Spring Boot 启用 HTTPS（可选）

`application-prod.yml` 示例:

```yaml
server:
  ssl:
    enabled: true
    key-store: classpath:mamoji.p12
    key-store-password: ${SSL_KEYSTORE_PASSWORD}
    key-store-type: PKCS12
    key-alias: mamoji
```

## 4. 反向代理 HTTPS（推荐）

生产环境建议由 Nginx/Caddy 终止 TLS，后端只监听内网端口。

### 4.1 Nginx 最小配置示例

```nginx
server {
    listen 443 ssl;
    server_name your-domain.com;

    ssl_certificate     /etc/nginx/ssl/mamoji.crt;
    ssl_certificate_key /etc/nginx/ssl/mamoji.key;

    location / {
        proxy_pass http://127.0.0.1:33000;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:38080;
    }
}
```

## 5. 安全建议

1. 生产环境优先使用受信 CA（如 Let's Encrypt）
2. 禁止将私钥提交到仓库
3. 通过环境变量注入证书密码
4. 定期检查证书过期时间并提前续期
