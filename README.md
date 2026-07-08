# blog-rag-api

Spring Boot RAG API for `yinsheng的小站`.

## Responsibilities

- `POST /api/chat`: JSON answer.
- `POST /api/chat/stream`: SSE answer used by the blog Chat Widget.
- Query embedding through `ai-compute-gateway`.
- Retrieve blog chunks from Qdrant.
- Return answer, citations and related posts.
- Run the incremental Markdown indexer with the `indexer` Spring profile.

## Main Environment Variables

- `AI_COMPUTE_BASE_URL`: internal AI Compute Gateway URL.
- `AI_COMPUTE_API_TOKEN`: internal service token.
- `RAG_CHAT_MODEL`: default `huihui-qwen3:4b-instruct-2507-abliterated-q4_K_M`.
- `RAG_EMBEDDING_MODEL`: default `bge-m3`.
- `QDRANT_URL`: Qdrant URL.
- `QDRANT_COLLECTION`: default `blog_chunks`.
- `BLOG_CONTENT_DIR`: Markdown source directory for the indexer.
- `INDEX_DB_PATH`: SQLite index status path.

## Local Build

```bash
mvn -B -DskipTests package
```

## Local Run

```bash
export AI_COMPUTE_BASE_URL=http://127.0.0.1:8081
export QDRANT_URL=http://127.0.0.1:6333
mvn spring-boot:run
```

Indexer:

```bash
export SPRING_PROFILES_ACTIVE=indexer
export BLOG_CONTENT_DIR=C:/IdeaProjects/blog/src/content/posts
export INDEX_DB_PATH=C:/IdeaProjects/blog/.rag/index-status.db
mvn spring-boot:run
```
