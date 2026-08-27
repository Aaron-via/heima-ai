package org.example.heimaai.repository;

import org.springframework.core.io.Resource;

public interface FileRepository {
    /*
     * 保存文件,且记录chatId和文件的映射关系
     * @param chatId
     * @param resource
     * @return 保存成功返回true
     */
    boolean save(String chatId, Resource resource);
    /*
     * 根据chatId获取文件
     * @param chatId
     * @return 找到的文件
     */
    Resource getFile(String chatId);
}
