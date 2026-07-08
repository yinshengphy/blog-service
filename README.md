# blog-rag-api

`yinsheng的小站` 的 Spring Boot RAG API。

## 职责

- `POST /api/chat`：返回 JSON 格式回答。
- `POST /api/chat/stream`：供博客聊天组件使用的 SSE 流式回答。
- 通过 `ai-compute-gateway` 生成查询向量。
- 从 Qdrant 检索博客切片。
- 返回回答、引用和相关文章。
- 使用 `indexer` Spring profile（配置档）运行增量 Markdown 索引器。

## 主要环境变量

- `AI_COMPUTE_BASE_URL`：内部 AI 计算网关地址。
- `AI_COMPUTE_API_TOKEN`：内部服务调用令牌。
- `RAG_CHAT_MODEL`：默认值 `huihui-qwen3:4b-instruct-2507-abliterated-q4_K_M`。
- `RAG_EMBEDDING_MODEL`：默认值 `bge-m3`。
- `QDRANT_URL`：Qdrant 地址。
- `QDRANT_COLLECTION`：默认值 `blog_chunks`。
- `BLOG_CONTENT_DIR`：索引器读取的 Markdown 源目录。
- `INDEX_DB_PATH`：SQLite 索引状态数据库路径。

## 本地构建

```bash
mvn -B -DskipTests package
```

## 本地运行

```bash
export AI_COMPUTE_BASE_URL=http://127.0.0.1:8081
export QDRANT_URL=http://127.0.0.1:6333
mvn spring-boot:run
```

索引器：

```bash
export SPRING_PROFILES_ACTIVE=indexer
export BLOG_CONTENT_DIR=C:/IdeaProjects/blog/src/content/posts
export INDEX_DB_PATH=C:/IdeaProjects/blog/.rag/index-status.db
mvn spring-boot:run
```
