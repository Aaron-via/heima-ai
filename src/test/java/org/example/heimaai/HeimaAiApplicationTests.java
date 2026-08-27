package org.example.heimaai;

import org.example.heimaai.utils.VectorDistanceUtils;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.FileSystem;
import java.util.*;

@SpringBootTest
class HeimaAiApplicationTests {

    @Autowired
    private OpenAiEmbeddingModel embeddingModel;

    @Autowired
    private VectorStore vectorStore;

    @Test
    public void contextLoads() {
        float[] floats = embeddingModel.embed("她站在河岸上");
        System.out.println(Arrays.toString(floats));
    }

    @Test
    void testEmbedding() {

// 1. 定义查询文本和待比较的句子集合

        String query = "global conflicts";

        String[] texts = {

                "俄乌战争持续升级，双方在顿巴斯地区激烈交火。",

                "联合国安理会就巴以冲突召开紧急会议。",

                "中美贸易战导致全球经济不确定性增加。",

                "日本航空基地检测出有机氟化物超标。", // 高混淆性：含“冲突”但非国际

                "这部电影展现了主角内心的矛盾与冲突。",     // 高混淆性：含“冲突”但非国际

                "下午三点有两个会议时间冲突，需要重新安排。", // 高混淆性：含“冲突”但非国际

                "我家猫又偷偷跳上沙发睡觉了。",

                "今年秋冬流行大地色系的大衣穿搭。",

                "春天到了，该给阳台的玫瑰花修剪枝条了。",

                "我国首次在空间站开展舱外生物辐射暴露实验。"

        };
// 2. 向量化
// 2.1 先将查询文本向量化
        float[] queryVector = embeddingModel.embed(query);

// 2.2 再将比较文本向量化，放到一个数组
        List<float[]> textVectors = embeddingModel.embed(Arrays.asList(texts));

// 3. 比较欧氏距离
        System.out.println("========== 欧氏距离（越小越相关） ==========");
// 3.1 把查询文本自己与自己比较，肯定是相似度最高的（距离应为0）
        System.out.printf("查询文本自己比较: %.4f%n", VectorDistanceUtils.euclideanDistance(queryVector, queryVector));
// 3.2 把查询文本与其它文本比较
        for (int i = 0; i < textVectors.size(); i++) {
            double distance = VectorDistanceUtils.euclideanDistance(queryVector, textVectors.get(i));
            System.out.printf("【%s】 %.4f%n", texts[i], distance);
        }

        System.out.println("--------------------------------------------");

// 4. 比较余弦距离
        System.out.println("========== 余弦距离（越小越相关） ==========");
// 4.1 把查询文本自己与自己比较，肯定是相似度最高的（距离应为0）
        System.out.printf("查询文本自己比较: %.4f%n", VectorDistanceUtils.cosineDistance(queryVector, queryVector));
// 4.2 把查询文本与其它文本比较
        for (int i = 0; i < textVectors.size(); i++) {
            double distance = VectorDistanceUtils.cosineDistance(queryVector, textVectors.get(i));
            System.out.printf("【%s】 %.4f%n", texts[i], distance);
        }

    }

    @Test
    public void testVectorStore() {
        Resource resource=new FileSystemResource("中二知识笔记.pdf");
        //1.创建pdf读取器

        PagePdfDocumentReader reader = new PagePdfDocumentReader(
                resource,
                PdfDocumentReaderConfig.builder()
                        .withPageExtractedTextFormatter(ExtractedTextFormatter.defaults())
                        .withPagesPerDocument(1)
                        .build());
        // 2.读取pdf文档，拆分document
        List<Document> documents = reader.read();
//看数据
        System.out.println(">>>> 准备写入 Redis 的文档数量: " + documents.size());
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            String text = doc.getText();
            String preview = text.length() > 60 ? text.substring(0, 60) : text;
            System.out.println("文档索引: " + i
                    + ", 页码: " + doc.getMetadata().get("page_number")
                    + ", 文件名: " + doc.getMetadata().get("file_name")
                    + ", 内容预览: " + preview);
        }
        //清理旧数据
        try (redis.clients.jedis.Jedis jedis = new redis.clients.jedis.Jedis("localhost", 6379)) {
            String cursor = "0";
            int count = 0;
            do {
                redis.clients.jedis.resps.ScanResult<String> scanResult =
                        jedis.scan(cursor, new redis.clients.jedis.params.ScanParams().match("custom-prefix*").count(100));
                cursor = scanResult.getCursor();
                for (String key : scanResult.getResult()) {
                    jedis.unlink(key);
                    count++;
                }
            } while (!cursor.equals("0"));
            System.out.println("已清理 " + count + " 条旧数据");
        }
        //3.写入向量库

        System.out.println(">>> 开始执行 vectorStore.add(documents)...");
        try {
            vectorStore.add(documents);
            System.out.println("<<< vectorStore.add(documents) 执行完毕，无异常抛出");
        } catch (Exception e) {
            System.out.println("!!! vectorStore.add 抛出异常: " + e.getMessage());
            e.printStackTrace();
        }

        //jedis检查法
        try (Jedis jedis = new Jedis("localhost", 6379)) {
            // 先测试连接是否正常
            System.out.println("Redis ping: " + jedis.ping());

            // 再扫描 key
            String cursor = "0";
            int count = 0;
            do {
                ScanResult<String> scanResult = jedis.scan(cursor, new ScanParams().match("custom-prefix*").count(100));
                cursor = scanResult.getCursor();
                for (String key : scanResult.getResult()) {
                    System.out.println("Key: " + key);
                    count++;
                }
            } while (!cursor.equals("0"));
            System.out.println("共找到 " + count + " 个 key");
        }
        // 在 add 之后，用 Jedis 直接查看 Redis 中有多少条数据
        try (redis.clients.jedis.Jedis jedis = new redis.clients.jedis.Jedis("localhost", 6379)) {
            // 使用 SCAN 遍历所有 custom-prefix 开头的 key
            String cursor = "0";
            int count = 0;
            do {
                redis.clients.jedis.resps.ScanResult<String> scanResult =
                        jedis.scan(cursor, new redis.clients.jedis.params.ScanParams().match("custom-prefix*").count(100));
                cursor = scanResult.getCursor();
                count += scanResult.getResult().size();
                for (String key : scanResult.getResult()) {
                    System.out.println("Found key: " + key + ", type: " + jedis.type(key));
                }
            } while (!cursor.equals("0"));
            System.out.println("Total keys found: " + count);
        }
        // 检查每个文档的实际内容长度和元数据
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            System.out.println("文档 " + i + ": ID=" + doc.getId()
                    + ", 内容长度=" + doc.getText().length()
                    + ", page_number=" + doc.getMetadata().get("page_number")
                    + ", file_name=" + doc.getMetadata().get("file_name"));
        }
        //4.搜索
        SearchRequest searchRequest = SearchRequest.builder()
                .query("论语中教育的目的是什么")
                .topK(5)
                .similarityThreshold(0.6)
                //.filterExpression("file_name=='中二知识笔记.pdf'")
                .build();
        List<Document> docs=vectorStore.similaritySearch(searchRequest);

        List<Document> sortedDocs = new ArrayList<>(docs);

        // 现在可以对复制后的列表排序
        sortedDocs.sort((d1, d2) -> Double.compare(d2.getScore(), d1.getScore()));
        System.out.println("=== 搜索结果（按匹配度从高到低）===");
        for (int i = 0; i < sortedDocs.size(); i++) {
            Document doc = sortedDocs.get(i);
            System.out.println("排名 " + (i + 1) + ":");
            System.out.println("ID: " + doc.getId());
            System.out.println("匹配度: " + String.format("%.4f", doc.getScore()));
            System.out.println("内容: " + doc.getText());
            System.out.println("-----------------------------");
        }
        if(docs==null||docs.isEmpty()){
            System.out.println("没有找到相关文档");
        }
    }
}
