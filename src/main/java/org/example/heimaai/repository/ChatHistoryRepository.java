package org.example.heimaai.repository;

import java.util.List;

public interface ChatHistoryRepository {
    /**
     * 保存会话记录
     * @param type 业务类型，如chat，service，pdf
     * @param chatId 会话id
     */
    void save(String type,String chatId);

    /**
     * 获取会话Id列表
     * @param type 业务类型
     * @return 会话id列表
     */
    List<String> getChatIds(String type);
}
