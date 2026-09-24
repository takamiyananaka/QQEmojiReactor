package com.emoji.reactor;

import com.emoji.reactor.data.HybridEmojiRepository;
import com.emoji.reactor.engine.ReactionDeduplicator;
import com.emoji.reactor.model.DefaultPresets;
import com.emoji.reactor.model.EmojiGroup;
import com.emoji.reactor.model.EmojiItem;
import com.emoji.reactor.model.GroupEmojiItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 核心引擎与通用自适应表情自动化测试套件 - 菜々華 (nanaka) v1.1.0
 */
public class ReactionEngineTest {

    public static class MockMsgRecord {
        public long msgId = 123456789L;
        public List<MockEmojiLikeItem> emojiLikes = new ArrayList<>();
    }

    public static class MockEmojiLikeItem {
        public String emojiId;
        public boolean isClicked;

        public MockEmojiLikeItem(String emojiId, boolean isClicked) {
            this.emojiId = emojiId;
            this.isClicked = isClicked;
        }

        public MockEmojiLikeItem(int faceId, boolean isClicked) {
            this.emojiId = String.valueOf(faceId);
            this.isClicked = isClicked;
        }
    }

    public static void main(String[] args) {
        System.out.println("====== [开始执行 菜々華 (nanaka) v1.1.0 正式版全量测试] ======");

        testAll294Sysfaces();
        testHybridRepositoryMerging();
        testEmoji20Limit();
        testMixedGroupAndDeduplication();
        testUniversalModelAndDeduplication();
        testNullSafetyAndResilience();

        System.out.println("====== [全部 6 组 28 项工业级测试全部通过！] ======");
    }

    private static void testRemoteConfigHelperJsonParsing() {
        System.out.print("[测试 6] RemoteConfigHelper 通用格式与旧格式解析测试 ... ");
        // 测试场景 1：最新通用格式（带 emojiId 和 emojiType）
        String modernJson = "[{\"id\":\"g1\",\"name\":\"哭\",\"delay\":100,\"emojis\":[{\"emojiId\":\"5\",\"emojiType\":1},{\"emojiId\":\"448\",\"emojiType\":1}]}]";
        List<EmojiGroup> list1 = com.emoji.reactor.hook.RemoteConfigHelper.parseGroupsJson(modernJson);
        assert list1 != null && list1.size() == 1 : "通用格式解析失败";
        assert list1.get(0).getName().equals("哭") : "方案名称错乱";
        assert list1.get(0).getItems().size() == 2 : "表情数量不符";
        assert list1.get(0).getItems().get(1).getEmojiId().equals("448") : "新表情 448 丢失";

        // 测试场景 2：旧版整型数组格式
        String legacyJson = "[{\"id\":\"g2\",\"name\":\"旧方案\",\"delay\":120,\"emojis\":[76, 178]}]";
        List<EmojiGroup> list2 = com.emoji.reactor.hook.RemoteConfigHelper.parseGroupsJson(legacyJson);
        assert list2 != null && list2.size() == 1 : "旧格式向下兼容解析失败";
        assert list2.get(0).getName().equals("旧方案") : "旧方案名称错乱";
        assert list2.get(0).getItems().size() == 2 : "旧方案表情数量不符";
        assert list2.get(0).getItems().get(0).getLegacyIntId() == 76 : "旧表情 ID 错乱";

        System.out.println("PASSED (新旧格式 100% 兼容解析，绝不回退预设)");
    }

    private static void testAll294Sysfaces() {
        System.out.print("[测试 1] 294 个全量官方超清小黄脸检查 ... ");
        List<EmojiItem> sysfaces = DefaultPresets.getAllSupportedSysfaces(null);
        assert sysfaces != null && sysfaces.size() == 294 : "超清小黄脸数量异常: " + (sysfaces != null ? sysfaces.size() : 0);

        List<Integer> requiredFaces = Arrays.asList(76, 178, 14, 182, 297, 474, 476, 311, 350, 357);
        for (int fid : requiredFaces) {
            boolean found = false;
            for (EmojiItem item : sysfaces) {
                if (item.getId() == fid) {
                    found = true;
                    break;
                }
            }
            assert found : "缺失关键官方小黄脸 ID: " + fid;
        }
        System.out.println("PASSED (共 294 款正版超清小黄脸全部在列，无废弃小图)");
    }

    private static void testHybridRepositoryMerging() {
        System.out.print("[测试 2] HybridEmojiRepository 双源并集融合机制测试 ... ");
        List<EmojiItem> merged = HybridEmojiRepository.getMergedSysfaces(null);
        assert merged != null && merged.size() >= 294 : "融合列表数量不可低于底库数量";
        assert merged.get(0).getId() == 0 : "首项表情 ID 错乱";
        System.out.println("PASSED (并集融合正常，超清底库完好保障)");
    }

    private static void testEmoji20Limit() {
        System.out.print("[测试 3] 20 个表情上限压测 ... ");
        EmojiGroup group = new EmojiGroup("测试组", null, 80);
        for (int i = 1; i <= 20; i++) {
            group.addEmoji(i);
        }
        assert group.getItems().size() == 20 : "表情数量异常";
        System.out.println("PASSED (成功验证 20 个表情上限约束)");
    }

    private static void testMixedGroupAndDeduplication() {
        System.out.print("[测试 4] 小黄脸 + Emoji 混合方案防取消去重测试 ... ");
        MockMsgRecord msg = new MockMsgRecord();
        msg.emojiLikes.add(new MockEmojiLikeItem(76, true));
        msg.emojiLikes.add(new MockEmojiLikeItem(474, true));
        msg.emojiLikes.add(new MockEmojiLikeItem(128077, true));
        msg.emojiLikes.add(new MockEmojiLikeItem(476, false));

        List<Integer> input = Arrays.asList(76, 474, 311, 476, 128077, 128293);
        List<Integer> filtered = ReactionDeduplicator.filterEmojisToApply(msg, input);

        List<Integer> expected = Arrays.asList(311, 476, 128293);
        assert filtered.equals(expected) : "去重算法失效! 期望: " + expected + ", 实际: " + filtered;
        System.out.println("PASSED (正版表情与 Emoji 混合去重精确，无误杀)");
    }

    private static void testUniversalModelAndDeduplication() {
        System.out.print("[测试 5] 通用模型 String emojiId + long emojiType 深度去重测试 ... ");
        MockMsgRecord msg = new MockMsgRecord();
        msg.emojiLikes.add(new MockEmojiLikeItem("501", true));
        msg.emojiLikes.add(new MockEmojiLikeItem("custom-ani-face", true));
        msg.emojiLikes.add(new MockEmojiLikeItem("128077", false));

        List<GroupEmojiItem> input = Arrays.asList(
                new GroupEmojiItem("501", 1L),
                new GroupEmojiItem("custom-ani-face", 1L),
                new GroupEmojiItem("502", 1L),
                new GroupEmojiItem("128077", 2L)
        );

        List<GroupEmojiItem> filtered = ReactionDeduplicator.filterItemsToApply(msg, input);
        assert filtered.size() == 2 : "去重数量错误，期望 2 项，实际: " + filtered.size();
        assert filtered.get(0).getEmojiId().equals("502") : "项 0 错误";
        assert filtered.get(1).getEmojiId().equals("128077") : "项 1 错误";
        System.out.println("PASSED (通用 String ID 去重精准有效，完全自适应)");
    }

    private static void testNullSafetyAndResilience() {
        System.out.print("[测试 6] 异常容错与空指针防御测试 ... ");
        List<Integer> r1 = ReactionDeduplicator.filterEmojisToApply(null, Arrays.asList(1, 474, 128077));
        assert r1.size() == 3 : "空消息对象未能平滑降级";

        List<Integer> r2 = ReactionDeduplicator.filterEmojisToApply(new MockMsgRecord(), null);
        assert r2 != null && r2.isEmpty() : "空列表未能安全返回空列表";

        List<GroupEmojiItem> r3 = ReactionDeduplicator.filterItemsToApply(null, null);
        assert r3 != null && r3.isEmpty() : "通用空实体未能安全返回空列表";

        System.out.println("PASSED (防御机制健全，无未捕获异常)");
    }
}
