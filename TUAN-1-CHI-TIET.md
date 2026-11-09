# Tuần 1 chi tiết — Dockerize order-service + payment-service

## Trước khi bắt đầu: Portfolio là gì, lưu ở đâu

Portfolio trong roadmap này = **các repo GitHub riêng biệt**, mỗi repo là một dự án hoàn chỉnh, chứng minh bạn làm được một năng lực cụ thể. Nhà tuyển dụng sẽ vào GitHub profile của bạn xem trực tiếp, không phải file PDF liệt kê kỹ năng.

**Cách tổ chức:**
- Tạo GitHub account công khai (nếu chưa có) — đây sẽ là "hồ sơ" chính khi ứng tuyển
- Mỗi giai đoạn = 1 repo riêng, đặt tên rõ ràng theo đúng gợi ý trong roadmap gốc, ví dụ: `springboot-docker`, `springboot-aws`, `terraform-aws`, `k8s-microservice`...
- Mỗi repo bắt buộc có `README.md` gồm: repo này làm gì, kiến trúc (vẽ sơ đồ đơn giản bằng text hoặc ảnh), cách chạy local, các quyết định kỹ thuật quan trọng (vì sao chọn cách này chứ không cách khác)
- Commit thường xuyên với message rõ ràng (không commit 1 lần "done") — nhà tuyển dụng xem lịch sử commit để đánh giá quá trình làm việc thật, không phải copy-paste
- Cuối cùng có thể tạo 1 repo tổng (ví dụ `platform-engineer-portfolio`) chỉ chứa README liệt kê + link tới 10 repo con, dùng làm điểm vào khi gửi CV

Repo của tuần này sẽ là `springboot-docker` — chính là project đầu tiên trong danh sách 10 project ở roadmap gốc.

---

## Chủ đề 1: Docker Multi-stage Build

**Nội dung phải học:**
- Vì sao 1 image build thẳng từ `gradle build` bị nặng (300-500MB) — vì nó mang theo cả Gradle, JDK full, cache dependency
- Multi-stage: stage 1 dùng image `gradle:8.7-jdk17` (hoặc tương ứng bản Java bạn dùng) để build ra file `.jar`; stage 2 dùng image nhẹ `eclipse-temurin:17-jre-alpine` chỉ copy file `.jar` từ stage 1 sang, không mang theo toolchain build
- `COPY --from=build` để lấy artifact giữa các stage
- `.dockerignore` để loại `build/`, `.gradle/`, `.git/`, `.idea/` khỏi build context (tăng tốc build)
- Layer caching: đặt `COPY build.gradle settings.gradle` + `COPY gradle ./gradle` và `RUN gradle dependencies` trước khi `COPY src/` — để Docker cache lại dependency, không tải lại mỗi lần code đổi

**Output cụ thể:**
- File `Dockerfile` cho order-service, file `Dockerfile` cho payment-service (2 file riêng, mỗi service 1 repo con hoặc 1 thư mục riêng trong repo `springboot-docker`)
- Chạy `docker build -t order-service:v1 .` thành công
- So sánh image size: build 1 lần theo cách thường (single-stage) và 1 lần multi-stage, ghi lại số MB chênh lệch vào README — đây là bằng chứng cụ thể cho thấy bạn hiểu vì sao multi-stage quan trọng

---

## Chủ đề 2: Docker Network + Volume

**Nội dung phải học:**
- Docker network driver `bridge` (mặc định) vs network riêng do bạn tạo — vì sao container cần network riêng để gọi nhau qua tên service thay vì IP
- `docker network create app-network`, sau đó chạy container với `--network app-network`
- DNS nội bộ của Docker: container tên `order-db` có thể được gọi bởi container khác chỉ bằng hostname `order-db`, không cần biết IP
- Volume: phân biệt named volume (`docker volume create`) và bind mount (map thư mục host vào container) — dùng named volume cho Postgres data để không mất dữ liệu khi container bị xóa
- Healthcheck: `HEALTHCHECK` trong Dockerfile hoặc trong Compose để biết container đã sẵn sàng nhận traffic chưa (quan trọng vì Postgres cần vài giây để khởi động, nếu app connect sớm quá sẽ lỗi)

**Output cụ thể:**
- Tạo network + volume bằng lệnh thủ công (không qua Compose) để hiểu rõ cơ chế trước
- Chứng minh: chạy `docker exec` vào container order-service, `ping order-db` thành công qua tên, không qua IP
- Chứng minh: xóa container Postgres rồi tạo lại, data vẫn còn (nhờ named volume) — chụp lại kết quả query trước/sau, ghi vào README

---

## Chủ đề 3: Docker Compose multi-service

**Nội dung phải học:**
- Cấu trúc file `docker-compose.yml`: `services`, `networks`, `volumes` ở top-level
- `depends_on` với điều kiện `condition: service_healthy` (không chỉ đợi container start mà đợi healthcheck pass)
- Biến môi trường: dùng file `.env` để không hardcode password/port trong file compose, dùng cú pháp `${VAR_NAME}` trong yaml
- Đặt tên service trùng với hostname mà Spring Boot dùng trong `application.yml` (`spring.datasource.url=jdbc:postgresql://order-db:5432/...`)
- Database-per-service trong Compose: 2 service Postgres riêng (`order-db`, `payment-db`), mỗi cái map port khác nhau ra host để tránh xung đột (`5433:5432` và `5434:5432`) nhưng bên trong network dùng port mặc định 5432

**Output cụ thể:**
- 1 file `docker-compose.yml` duy nhất khởi động: order-service, payment-service, order-db (Postgres), payment-db (Postgres), redis
- Chạy `docker-compose up -d`, cả 5 container lên healthy
- Gọi thử 1 API của order-service qua Postman/curl, xác nhận nó ghi được vào `order-db`

---

## Chủ đề 4: Kiểm tra tính đúng đắn của isolation (database-per-service)

**Nội dung phải học:**
- Vì sao database-per-service quan trọng trong microservices: tránh 1 service sửa schema làm gãy service khác, cho phép scale/chọn loại DB độc lập
- Cách kiểm tra thực tế: từ container order-service, thử connect vào `payment-db` bằng thông tin credential của `order-db` — phải thất bại (khác network scope hoặc khác credential)
- Retry connection: Spring Boot có cơ chế retry kết nối DB (HikariCP có config `connection-timeout`, `initialization-fail-timeout`) — tìm hiểu config này để service không crash ngay khi DB chưa sẵn sàng

**Output cụ thể:**
- Ghi lại 1 đoạn log/ảnh chụp chứng minh order-service KHÔNG truy cập được payment-db (proof of isolation)
- Thử tắt `payment-db` trong lúc payment-service đang chạy, quan sát log xem service có tự retry hay crash luôn — ghi nhận xét vào README (đây là kiến thức nền cho resilience ở Kubernetes sau này)

---

## Portfolio cuối tuần — repo `springboot-docker`

Cấu trúc thư mục gợi ý:
```
springboot-docker/
├── order-service/
│   └── Dockerfile
├── payment-service/
│   └── Dockerfile
├── docker-compose.yml
├── .env.example
├── .dockerignore
└── README.md
```

(mỗi service dùng `build.gradle`/`build.gradle.kts` sẵn có trong repo gốc của bạn, không cần thêm gì ở đây ngoài `Dockerfile`)

**README.md phải trả lời được:**
1. Repo này chứng minh năng lực gì? (Dockerize Spring Boot microservices theo database-per-service, multi-stage build, Compose orchestration)
2. Cách chạy: `cp .env.example .env && docker-compose up -d`
3. Kiến trúc: vẽ đơn giản luồng order-service → order-db, payment-service → payment-db, cả 2 dùng chung redis
4. Kết quả đo được: image size trước/sau multi-stage, bằng chứng isolation

**Tiêu chí hoàn thành tuần 1 (để tự chấm):**
- [ ] Clone lại repo `springboot-docker` trên máy khác (hoặc xóa hết container/image rồi build lại), chạy được trong 30 phút không cần tra cứu thêm
- [ ] Giải thích được cho người khác nghe: multi-stage build giải quyết vấn đề gì, vì sao cần network riêng, vì sao named volume giữ được data
