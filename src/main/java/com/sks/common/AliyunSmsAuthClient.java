package com.sks.common;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 阿里云 DYPNS 短信认证实现（spec §3.3，替换 dysmsapi 版 AliyunSmsClient）。
 *
 * <p>字面码模式：{@code TemplateParam={"code":"<6>","min":"5"}}，阿里云只发不核验，Java 自生成码 + 自比对
 * （不调 CheckSmsVerifyCode）。条件降级：access-key/secret/sign + 对应 scene 模板任一空 → stub 不抛；
 * 齐全 → 真 SendSmsVerifyCode，失败（body.Code≠OK 或异常）抛 SMS_SEND_FAILED。懒构造 Client；setDelegate 测试 seam。
 *
 * <p><b>签名不走 .env / 环境变量</b>：{@code sign-name} 是全项目唯一的非 ASCII 配置项，值写死在
 * application.yml（YAML 按 UTF-8 读）。经 .env 会被 properties 加载器按 ISO-8859-1 读成乱码 → 阿里云回
 * 「签名或者模版无效」；松散绑定下 {@code SKS_SMS_SIGN_NAME} 环境变量也有同类风险，见 {@link #warnIfMangled}。
 *
 * <p><b>不设 CodeLength/CodeType/Interval</b>：那仨是「{@code ##code##} 由系统按 CodeType 生成」模式的参数
 * （PNVS 文档「由参数 CodeType 指定验证码生成规则」）。字面码模式直接传 {@code {"code":"123456",...}}
 * （文档「也可直接传入指定的验证码值」），设了 Code 参数反而让 PNVS 把模板判为
 * {@code isv.INVALID_PARAMETERS「签名或者模版无效」}（实测）。
 */
@Component
public class AliyunSmsAuthClient implements SmsClient {

    private static final Logger log = LoggerFactory.getLogger(AliyunSmsAuthClient.class);

    private final String accessKeyId;
    private final String accessKeySecret;
    private final String endpoint;
    private final String signName;
    private final String templateLogin;
    private final String templateVerifyOld;
    private final String templateBindNew;

    private Client override;
    private Client built;

    public AliyunSmsAuthClient(
            @Value("${sks.sms.access-key-id:}") String accessKeyId,
            @Value("${sks.sms.access-key-secret:}") String accessKeySecret,
            @Value("${sks.sms.endpoint:dypnsapi.aliyuncs.com}") String endpoint,
            @Value("${sks.sms.sign-name:}") String signName,
            @Value("${sks.sms.template-login:}") String templateLogin,
            @Value("${sks.sms.template-verify-old:}") String templateVerifyOld,
            @Value("${sks.sms.template-bind-new:}") String templateBindNew) {
        this.accessKeyId = accessKeyId;
        this.accessKeySecret = accessKeySecret;
        this.endpoint = endpoint;
        this.signName = signName;
        this.templateLogin = templateLogin;
        this.templateVerifyOld = templateVerifyOld;
        this.templateBindNew = templateBindNew;
    }

    @Override
    public void sendVerificationCode(String phone, String code, SmsScene scene) {
        String templateCode = templateFor(scene);
        if (!configured(templateCode)) {
            log.info("[SMS-STUB] scene={} phone={} code={} (no ALIYUN key/template)", scene, phone, code);
            return;
        }
        send(phone, templateCode, code);
    }

    private void send(String phone, String templateCode, String code) {
        SendSmsVerifyCodeRequest req = new SendSmsVerifyCodeRequest()
                .setPhoneNumber(phone)
                .setSignName(signName)
                .setTemplateCode(templateCode)
                // 字面码模式：直接传具体验证码值（PNVS 文档「也可直接传入指定的验证码值，直接下发至接收方」）。
                // 不设 CodeLength/CodeType/Interval——那仨是「##code## 由系统按 CodeType 生成」模式的参数，
                // 与字面码同发会让 PNVS 把模板判为 isv.INVALID_PARAMETERS「签名或者模版无效」（实测）。
                // 验证码由 Java 生成、存 sms_code、自比对，不调 CheckSmsVerifyCode。
                .setTemplateParam("{\"code\":\"" + code + "\",\"min\":\"5\"}");
        warnIfMangled(signName);
        try {
            SendSmsVerifyCodeResponse resp = delegate().sendSmsVerifyCodeWithOptions(req, new RuntimeOptions());
            String c = resp.getBody().getCode();
            if (!"OK".equals(c)) {
                log.warn("AliyunSmsAuth send failed: phone={}, code={}, msg={}",
                        phone, c, resp.getBody().getMessage());
                throw new BizException(ErrorCode.SMS_SEND_FAILED);
            }
            log.info("AliyunSmsAuth sent: phone={}, bizId={}", phone,
                    resp.getBody().getModel() == null ? null : resp.getBody().getModel().getBizId());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("AliyunSmsAuth send exception: phone={}: {}", phone, e.getMessage());
            throw new BizException(ErrorCode.SMS_SEND_FAILED);
        }
    }

    /**
     * 识别「UTF-8 被按 ISO-8859-1 读」的乱码签名，把一个无从下手的云端报错变成一句能直接照做的日志。
     *
     * <p>触发过一次真实故障：签名曾放在 {@code .env}，而本地 {@code spring.config.import} 的
     * {@code [.properties]} 走 Java properties 加载器（ISO-8859-1），「恒创联众」的 12 个 UTF-8 字节被读成
     * 12 个字符，SDK 再按 UTF-8 编码发出 → 阿里云只回 {@code isv.INVALID_PARAMETERS「签名或者模版无效」}，
     * 完全看不出是编码问题（同一签名用示例代码硬编码则正常，因为字面量走的不是这条路）。
     *
     * <p>现在值写死在 application.yml，这条路已堵；但 Spring 松散绑定下 {@code SKS_SMS_SIGN_NAME} 环境变量
     * 仍能覆盖该属性，容器里的环境变量解码同样依赖 locale，故此检查保留。
     *
     * <p>判据精确、几乎不会误报：乱码后每个字节各占一个字符，全部落在 U+0080..U+00FF；而真实中文签名在
     * U+4E00 以上。所以「有非 ASCII 字符 且 无一字符超过 U+00FF」只可能是这种误读。
     */
    private static void warnIfMangled(String sign) {
        boolean hasNonAscii = false;
        for (int i = 0; i < sign.length(); i++) {
            char c = sign.charAt(i);
            if (c > 0xFF) {
                return;
            }
            if (c > 0x7F) {
                hasNonAscii = true;
            }
        }
        if (hasNonAscii) {
            log.error("短信签名疑似编码错误（UTF-8 被按 ISO-8859-1 读）：'{}'，阿里云将回「签名或者模版无效」。"
                    + "修法：把 ALIYUN_SMS_SIGN 从 .env 删掉，签名默认值在 application.yml 的 sks.sms.sign-name"
                    + "（YAML 按 UTF-8 读，不会被这样误读）。", sign);
        }
    }

    private String templateFor(SmsScene scene) {
        return switch (scene) {
            case LOGIN_REGISTER -> templateLogin;
            case VERIFY_OLD_PHONE -> templateVerifyOld;
            case BIND_NEW_PHONE -> templateBindNew;
        };
    }

    private boolean configured(String templateCode) {
        return isPresent(accessKeyId) && isPresent(accessKeySecret)
                && isPresent(signName) && isPresent(templateCode);
    }

    private static boolean isPresent(String s) {
        return s != null && !s.isBlank();
    }

    private Client delegate() {
        if (override != null) {
            return override;
        }
        if (built == null) {
            Config config = new Config()
                    .setAccessKeyId(accessKeyId)
                    .setAccessKeySecret(accessKeySecret);
            config.endpoint = endpoint;
            try {
                built = new Client(config);
            } catch (Exception e) {
                throw new BizException(ErrorCode.SMS_SEND_FAILED);
            }
        }
        return built;
    }

    /** 测试 seam：注入 mock Client，跳过懒构建。 */
    void setDelegate(Client delegate) {
        this.override = delegate;
    }
}
