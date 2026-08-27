package org.example.heimaai.utils;

import java.util.List;

/**
 * 向量距离计算工具类
 * 用于计算两个向量之间的欧氏距离和余弦距离（余弦相似度）
 */
public class VectorDistanceUtils {

    /**
     * 计算两个向量之间的欧氏距离
     * 公式：sqrt(Σ(ai - bi)²)
     *
     * @param vectorA 向量 A
     * @param vectorB 向量 B
     * @return 欧氏距离
     * @throws IllegalArgumentException 如果向量为空或长度不一致
     */
    public static double euclideanDistance(List<Double> vectorA, List<Double> vectorB) {
        validateVectors(vectorA, vectorB);

        double sum = 0.0;
        for (int i = 0; i < vectorA.size(); i++) {
            double diff = vectorA.get(i) - vectorB.get(i);
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }

    /**
     * 计算两个向量之间的欧氏距离（接收 float 数组）
     */
    public static double euclideanDistance(float[] vectorA, float[] vectorB) {
        validateVectors(vectorA, vectorB);

        double sum = 0.0;
        for (int i = 0; i < vectorA.length; i++) {
            double diff = vectorA[i] - vectorB[i];
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }

    /**
     * 计算两个向量之间的余弦相似度
     * 公式：cos(θ) = (A·B) / (||A|| × ||B||)
     * 返回值范围：[-1, 1]，越接近 1 表示越相似
     *
     * @param vectorA 向量 A
     * @param vectorB 向量 B
     * @return 余弦相似度
     * @throws IllegalArgumentException 如果向量为空或长度不一致
     */
    public static double cosineSimilarity(List<Double> vectorA, List<Double> vectorB) {
        validateVectors(vectorA, vectorB);

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < vectorA.size(); i++) {
            double a = vectorA.get(i);
            double b = vectorB.get(i);
            dotProduct += a * b;
            normA += a * a;
            normB += b * b;
        }

        // 防止除零错误
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 计算两个向量之间的余弦相似度（接收 float 数组）
     */
    public static double cosineSimilarity(float[] vectorA, float[] vectorB) {
        validateVectors(vectorA, vectorB);

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < vectorA.length; i++) {
            double a = vectorA[i];
            double b = vectorB[i];
            dotProduct += a * b;
            normA += a * a;
            normB += b * b;
        }

        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 计算余弦距离（1 - 余弦相似度）
     * 距离范围：[0, 2]，越小表示越相似
     */
    public static double cosineDistance(List<Double> vectorA, List<Double> vectorB) {
        return 1.0 - cosineSimilarity(vectorA, vectorB);
    }

    public static double cosineDistance(float[] vectorA, float[] vectorB) {
        return 1.0 - cosineSimilarity(vectorA, vectorB);
    }

    // ========== 校验方法 ==========

    private static void validateVectors(List<Double> vectorA, List<Double> vectorB) {
        if (vectorA == null || vectorB == null) {
            throw new IllegalArgumentException("向量不能为 null");
        }
        if (vectorA.isEmpty() || vectorB.isEmpty()) {
            throw new IllegalArgumentException("向量不能为空");
        }
        if (vectorA.size() != vectorB.size()) {
            throw new IllegalArgumentException("两个向量的维度不一致: "
                    + vectorA.size() + " vs " + vectorB.size());
        }
    }

    private static void validateVectors(float[] vectorA, float[] vectorB) {
        if (vectorA == null || vectorB == null) {
            throw new IllegalArgumentException("向量不能为 null");
        }
        if (vectorA.length == 0 || vectorB.length == 0) {
            throw new IllegalArgumentException("向量不能为空");
        }
        if (vectorA.length != vectorB.length) {
            throw new IllegalArgumentException("两个向量的维度不一致: "
                    + vectorA.length + " vs " + vectorB.length);
        }
    }

    // ========== main 方法：测试不同向量之间的距离 ==========

    public static void main(String[] args) {
        // 模拟三个 4 维向量（方便肉眼验证）
        float[] v1 = {1.0f, 2.0f, 3.0f, 4.0f};
        float[] v2 = {1.0f, 2.0f, 3.0f, 4.0f};  // 与 v1 完全相同
        float[] v3 = {5.0f, 6.0f, 7.0f, 8.0f};  // 与 v1 方向相同但模长不同
        float[] v4 = {4.0f, 3.0f, 2.0f, 1.0f};  // 与 v1 反向

        System.out.println("========== 欧氏距离测试 ==========");
        System.out.printf("v1 vs v2（完全相同）: %.4f%n", euclideanDistance(v1, v2));
        System.out.printf("v1 vs v3（方向相同，模长不同）: %.4f%n", euclideanDistance(v1, v3));
        System.out.printf("v1 vs v4（反向）: %.4f%n", euclideanDistance(v1, v4));

        System.out.println();
        System.out.println("========== 余弦相似度测试 ==========");
        System.out.printf("v1 vs v2（完全相同）: %.4f%n", cosineSimilarity(v1, v2));
        System.out.printf("v1 vs v3（方向相同，模长不同）: %.4f%n", cosineSimilarity(v1, v3));
        System.out.printf("v1 vs v4（反向）: %.4f%n", cosineSimilarity(v1, v4));

        System.out.println();
        System.out.println("========== 余弦距离测试 ==========");
        System.out.printf("v1 vs v2（完全相同）: %.4f%n", cosineDistance(v1, v2));
        System.out.printf("v1 vs v3（方向相同，模长不" +
                "同）: %.4f%n", cosineDistance(v1, v3));
        System.out.printf("v1 vs v4（反向）: %.4f%n", cosineDistance(v1, v4));

        System.out.println();
        System.out.println("========== 用你之前给的 1024 维向量做测试 ==========");
        // 这里用你之前的那段向量的一部分做演示（取前 4 维）
        // 实际使用时传入完整向量即可
        float[] yourVector = {
                -0.0018028817f, 0.023061862f, -0.09570297f, -0.0411658f,
                -0.03126873f, -1.1862222E-4f, -0.023456242f, 0.098858014f
        };
        float[] sameDirection = {
                -0.00090144085f, 0.011530931f, -0.047851485f, -0.0205829f,
                -0.015634365f, -5.931111E-5f, -0.011728121f, 0.049429007f
        };

        System.out.printf("欧氏距离: %.4f%n", euclideanDistance(yourVector, sameDirection));
        System.out.printf("余弦相似度: %.4f%n", cosineSimilarity(yourVector, sameDirection));
        System.out.printf("余弦距离: %.4f%n", cosineDistance(yourVector, sameDirection));
    }
}