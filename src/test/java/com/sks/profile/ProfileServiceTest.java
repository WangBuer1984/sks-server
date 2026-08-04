package com.sks.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sks.AbstractDbTest;
import com.sks.aiclient.AiClient;
import com.sks.common.BizException;
import com.sks.kb.KbCardMapper;
import com.sks.kb.KbCardService;
import com.sks.user.AppUser;
import com.sks.user.AppUserMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ProfileService} 服务级集成测试——定位校准编排 + 档案 / A 层卡落库（§4.2 校准免费，无额度介入）。
 *
 * <p>真实 Testcontainers {@code pgvector/pgvector:pg16}（非 H2），Flyway 跑 V1 建
 * {@code positioning_profile} / {@code kb_card}。{@link AiClient} 用 {@code @MockBean} mock
 * ——不真正调 Python，{@code interviewResult} 桩返回带 2 张 A 层卡的 summarize 产出。
 *
 * <p><b>事务边界 + 测试隔离</b>（与 {@code ScriptServiceTest} 同款）：基类 {@link AbstractDbTest}
 * 是 {@code @Transactional}，但 {@link ProfileService#confirm} 的写入必须真提交才能被后续断言读到
 * （profile + 批量 A 卡）——故两个用例都标 {@code @Transactional(propagation = NOT_SUPPORTED)}
 * 挂起测试事务，让 {@link ProfileService#confirm} 内部的 {@code TransactionTemplate} 短事务独立提交；
 * {@link #cleanup} 显式清理已提交行（NOT_SUPPORTED 不随测试事务回滚）。校准免费，无额度路径，
 * 不存在「refund 落 rollback-only tx」的资金风险，但「confirm 真落库」仍需 NOT_SUPPORTED 才能证伪。
 *
 * <p>覆盖 brief 两条核心用例：
 * <ul>
 *   <li>{@code confirmPersistsProfileAndACards}：confirm 后 active 档案存在 + A 层卡数 = summarize 产出的 a_cards 数。
 *   <li>{@code reCalibrationKeepsOldVersionInactive}：两次 confirm（不同 session）→ 两条档案行，仅一条 active。
 * </ul>
 */
class ProfileServiceTest extends AbstractDbTest {

    @Autowired ProfileService profileService;
    @Autowired PositioningProfileMapper profileMapper;
    @Autowired KbCardService kbCardService;
    @Autowired KbCardMapper kbCardMapper;
    @Autowired AppUserMapper appUserMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean AiClient aiClient;

    private static final ObjectMapper OM = new ObjectMapper();

    private long uid;

    @BeforeEach
    void setup() {
        // A 层建卡走 safetyCheck（belt-and-suspenders，复用 KbCardService.create）；桩放行。
        when(aiClient.safetyCheck(any())).thenReturn(true);
        // confirm 调 interviewResult（GET 只读 checkpoint）取 summarize 产出；桩返回带 2 张 A 层卡。
        when(aiClient.interviewResult(any())).thenReturn(summarizeResultWith2Cards());

        AppUser u = new AppUser();
        u.setPhone("13800000222");
        u.setDefaultPlatform("douyin");
        appUserMapper.insert(u);
        uid = u.getId();
    }

    @AfterEach
    void cleanup() {
        // NOT_SUPPORTED 用例不随测试事务回滚，需显式清理已提交行（FK 安全顺序：kb_card → profile → app_user）。
        jdbcTemplate.update("DELETE FROM kb_card WHERE user_id = ?", uid);
        jdbcTemplate.update("DELETE FROM positioning_profile WHERE user_id = ?", uid);
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", uid);
    }

    // ---- brief verbatim 用例 -------------------------------------------------

    /** §4.2：confirm → 从 checkpoint 取 summarize 产出 → 写 active 档案 + 批量建 A 层卡。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void confirmPersistsProfileAndACards() {
        when(aiClient.interviewResult(any())).thenReturn(summarizeResultWith2Cards());
        profileService.confirm(uid, "sess-1", null);
        assertTrue(profileService.activeProfile(uid).isPresent());
        assertEquals(2, kbCardService.countByLayer(uid, "A"));
    }

    /** §4.2：再校准保留旧版本（active=false 留历史），仅一条 active；version 递增。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void reCalibrationKeepsOldVersionInactive() {
        profileService.confirm(uid, "s1", null); // v1
        profileService.confirm(uid, "s2", null); // v2
        assertEquals(2, profileMapper.countByUser(uid));
        assertEquals(1, profileMapper.countActiveByUser(uid)); // 只有一条 active
    }

    // ---- D2 Task 1：confirm 入库 turns + interview/history 只读端点 + activeProfile strip ----

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void confirmPersistsInterviewTurns() {
        when(aiClient.interviewResult(anyString())).thenReturn(summarizeResultWith2Cards());
        List<ProfileService.InterviewTurn> turns = List.of(
                new ProfileService.InterviewTurn("ai", "猜你人设是…对吗？"),
                new ProfileService.InterviewTurn("user", "基本对"));
        profileService.confirm(uid, "sess-1", turns);
        ProfileService.InterviewHistoryView v = profileService.interviewTurns(uid);
        assertTrue(v.found());
        assertEquals(2, v.turns().size());
        assertEquals("ai", v.turns().get(0).role());
        assertEquals("基本对", v.turns().get(1).text());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void confirmWithoutTurnsBackwardCompat() {
        when(aiClient.interviewResult(anyString())).thenReturn(summarizeResultWith2Cards());
        profileService.confirm(uid, "sess-1", null); // 旧契约
        ProfileService.InterviewHistoryView v = profileService.interviewTurns(uid);
        assertFalse(v.found()); // 旧档案无 _interview_turns
        assertTrue(v.turns().isEmpty());
    }

    @Test
    void activeProfileStripsUnderscoreKeys() {
        when(aiClient.interviewResult(anyString())).thenReturn(summarizeResultWith2Cards());
        List<ProfileService.InterviewTurn> turns = List.of(
                new ProfileService.InterviewTurn("ai", "q"));
        profileService.confirm(uid, "sess-1", turns);
        var content = profileService.activeProfileView(uid).content();
        assertTrue(content.containsKey("人设"));            // profile 键保留
        assertFalse(content.containsKey("_interview_turns")); // meta 键剥掉
    }

    @Test
    void interviewTurnsUncalibratedReturnsNotFound() {
        ProfileService.InterviewHistoryView v = profileService.interviewTurns(uid);
        assertFalse(v.found());
        assertTrue(v.turns().isEmpty());
    }

    // ---- sample-opening passthrough（brief Task 2）---------------------------

    /** §校准：sample-opening found=true 透传两版开场钩子（with → withHook accessor）。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sampleOpeningFoundReturnsHooks() {
        // 3rd arg topic is null in the service call; anyString() does NOT match null → use nullable.
        when(aiClient.sampleOpening(anyLong(), anyString(), nullable(String.class)))
                .thenReturn(
                        new AiClient.SampleOpeningResponse(
                                true, "报价为什么差一倍", "无档案版开头", "有档案版开头"));
        AiClient.SampleOpeningResponse resp =
                profileService.sampleOpening(uid, "sess-1", null);
        assertTrue(resp.found());
        assertEquals("报价为什么差一倍", resp.topic());
        assertEquals("有档案版开头", resp.withHook());
    }

    /** §校准：sample-opening found=false → PARAM_INVALID(4005)。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sampleOpeningNotFoundThrowsParamInvalid() {
        when(aiClient.sampleOpening(anyLong(), anyString(), nullable(String.class)))
                .thenReturn(new AiClient.SampleOpeningResponse(false, null, null, null));
        assertThrows(
                BizException.class,
                () -> profileService.sampleOpening(uid, "sess-1", null));
    }

    // ---- 辅助 ----------------------------------------------------------------

    /** 构造带 2 张 A 层卡的 summarize 产出（对齐 Python /ai/interview/result 响应形状）。 */
    private AiClient.InterviewResultResponse summarizeResultWith2Cards() {
        ObjectNode profile = OM.createObjectNode();
        profile.put("人设", "美妆成分党博主");
        profile.put("人群", "25-35 岁女性");
        profile.put("差异化", "成分深挖");
        profile.put("变现", "带货 + 课程");
        profile.put("红线", "不夸大功效");
        profile.put("支柱配比", "5:3:2");
        List<AiClient.CardGenCard> aCards =
                List.of(
                        new AiClient.CardGenCard(
                                "定位", "人设卡", OM.createObjectNode().put("desc", "成分党")),
                        new AiClient.CardGenCard(
                                "人设", "人群卡", OM.createObjectNode().put("desc", "25-35 女性")));
        return new AiClient.InterviewResultResponse(profile, aCards, true);
    }

    /** 防止未用编译告警（保留 Optional 引用，便于静态分析跟踪 activeProfile 契约）。 */
    @SuppressWarnings("unused")
    private void touchOptional(Optional<JsonNode> ignored) {}
}
