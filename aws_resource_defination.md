Dưới đây là cách hiểu “mỗi khái niệm trên AWS làm gì, tạo ra như thế nào, và ảnh hưởng lẫn nhau ra sao” theo kiểu bạn đang học roadmap tuần 2.

1. VPC — “lò sưởi / sân nhà” của ứng dụng trên AWS

- Làm gì:
  - VPC là mạng riêng ảo của bạn trong AWS.
  - Tất cả resource chính của dự án (EC2, RDS, ALB, NAT...) phải nằm trong một VPC.
  - Nó giống như “toà nhà riêng” của bạn trong AWS.

- Tạo trên AWS:
  - AWS Console → VPC → Create VPC
  - Chọn CIDR, ví dụ: 10.0.0.0/16
  - VPC này là căn cứ cho mọi thứ bạn tạo sau đó.

- Ảnh hưởng:
  - Nếu không có VPC thì resource không thể “ở chung một mạng”, không thể giao tiếp an toàn với nhau.
  - VPC quyết định mọi thứ: subnet nào public/private, route nào đi qua đâu, security group được mở ra sao.

2. CIDR — “địa chỉ mạng” của VPC/subnet

- Làm gì:
  - CIDR là cách chia không gian IP.
  - Ví dụ: 10.0.0.0/16 nghĩa là VPC có khoảng IP từ 10.0.0.0 đến 10.0.255.255.

- Tạo trên AWS:
  - Khi tạo VPC/subnet, bạn phải nhập CIDR.
  - Subnet ví dụ:
    - public subnet: 10.0.1.0/24
    - private subnet: 10.0.10.0/24

- Ảnh hưởng:
  - CIDR quyết định subnet có đủ IP không.
  - Nếu bạn chọn CIDR quá nhỏ thì later sẽ thiếu IP.
  - Nếu overlap CIDR với mạng nội bộ của bạn, sẽ gây xung đột routing.

3. Subnet — “phòng” trong VPC

- Làm gì:
  - Subnet là phân vùng nhỏ của VPC.
  - Có 2 kiểu quan trọng:
    - Public subnet: có thể tiếp xúc Internet
    - Private subnet: không mở trực tiếp ra Internet

- Tạo trên AWS:
  - VPC → Subnets → Create subnet
  - Chọn AZ (Availability Zone), ví dụ us-east-1a, us-east-1b
  - Gán subnet vào VPC

- Ảnh hưởng:
  - EC2 đặt ở public subnet thì app có thể public reach.
  - RDS đặt ở private subnet thì data được bảo vệ tốt hơn, tránh Internet đánh vào.
  - Một resource bên trong subnet có routing theo route table của subnet đó.

4. Route Table — “bản đồ chỉ đường”

- Làm gì:
  - Route table định nghĩa traffic đi đâu.
  - Mỗi subnet gắn với một route table.

- Tạo trên AWS:
  - VPC → Route Tables → Create route table
  - Thêm rule:
    - 0.0.0.0/0 -> IGW hoặc NAT
    - local route đến VPC CIDR

- Ảnh hưởng:
  - Public subnet cần route 0.0.0.0/0 -> IGW
  - Private subnet cần route 0.0.0.0/0 -> NAT
  - Nếu route table sai, service sẽ không ra Internet hoặc không truy cập được nhau.

5. Internet Gateway (IGW) — “cửa ra Internet”

- Làm gì:
  - Là cổng để VPC kết nối Internet.
  - Public subnet cần IGW để traffic từ Internet đi vào.

- Tạo trên AWS:
  - VPC → Internet Gateways → Create internet gateway
  - Attach vào VPC

- Ảnh hưởng:
  - Với public subnet, IGW cho phép:
    - user truy cập app
    - SSH vào EC2
    - ALB public target traffic

6. NAT Gateway — “cổng chỉ cho phép đi ra, không cho vào”

- Làm gì:
  - NAT cho private subnet đi ra Internet để download package, update, pull image, hoặc gọi các API bên ngoài.
  - Nhưng NAT không cho Internet “đánh thẳng vào” private subnet.

- Tạo trên AWS:
  - VPC → NAT Gateways → Create NAT Gateway
  - Thường phải đặt nó trong public subnet
  - Route table của private subnet trỏ 0.0.0.0/0 -> NAT

- Ảnh hưởng:
  - EC2 trong private subnet có thể ra ngoài, nhưng không thể bị public access trực tiếp.
  - RDS, redis, app private thường dùng NAT cho outbound.

7. Security Group — “tường lửa của instance”

- Làm gì:
  - Security Group là firewall cho EC2, ALB, RDS...
  - Stateful: nếu request outbound được cho phép thì return traffic tự động được phép.

- Tạo trên AWS:
  - EC2 → Security Groups → Create security group
  - Chọn inbound rules:
    - SSH 22 từ IP của bạn
    - HTTP 80/443 từ Internet hoặc ALB
    - DB port 5432 chỉ cho phép từ app SG

- Ảnh hưởng:
  - Nếu SG không mở port, app sẽ không kết nối được.
  - Nếu mở quá rộng, security kém.
  - SG là nơi “mở cánh cửa” cho resource có thể nhận traffic từ đâu.

8. NACL — “tường lửa của subnet”

- Làm gì:
  - NACL kiểm soát traffic vào/ra subnet.
  - Stateless: bạn phải khai báo cả inbound và outbound rule.

- Tạo trên AWS:
  - VPC → Network ACLs → Create

- Ảnh hưởng:
  - SG điều khiển app instance, NACL điều khiển subnet.
  - Không cần học sâu ngay đầu, nhưng cần biết:
    - SG = per resource
    - NACL = per subnet

9. IAM User — “người dùng trong AWS”

- Làm gì:
  - Dành cho con người hoặc hệ thống cần login vào AWS.
  - Ví dụ: bạn là IAM User.

- Tạo trên AWS:
  - IAM → Users → Create user
  - Gắn policy

- Ảnh hưởng:
  - Mỗi người nên có user riêng.
  - Không nên dùng root account làm việc thường ngày.
  - User nên có quyền tối thiểu cần thiết.

10. IAM Role — “quyền tạm thời cho resource”

- Làm gì:
  - Dành cho resource như EC2, Lambda, EKS.
  - Ví dụ EC2 instance gắn role để đọc S3 mà không cần key.

- Tạo trên AWS:
  - IAM → Roles → Create role
  - Chọn trusted entity = EC2
  - Gắn policy

- Ảnh hưởng:
  - App không cần hardcode credential.
  - Nếu role không được gắn đúng, app sẽ không truy cập được resource cần thiết.
  - Đây là cách AWS “bảo mật hơn, dễ quản lý hơn”.

11. IAM Policy — “lệnh quyền”

- Làm gì:
  - Policy mô tả quyền được phép làm gì.
  - Ví dụ:
    - S3 read-only
    - RDS full access
    - CloudWatch logs write

- Tạo trên AWS:
  - IAM → Policies → Create policy
  - JSON inline hoặc managed policy

- Ảnh hưởng:
  - Nếu policy cấp quá rộng, dễ bị lộ quyền.
  - Nếu policy thiếu quyền, resource không hoạt động.

12. EC2 — “máy chủ Linux thực sự”

- Làm gì:
  - EC2 là máy ảo trên AWS.
  - Bạn deploy Spring Boot app ở đây hoặc chạy service container.

- Tạo trên AWS:
  - EC2 → Launch instance
  - Chọn AMI, instance type, key pair, subnet, SG, IAM role
  - Có thể dùng user data để tự cài Java/Docker khi boot

- Ảnh hưởng:
  - App chạy trên EC2 cần:
    - public subnet nếu cần expose
    - SG cho phép SSH/HTTP
    - role nếu cần access S3
  - Nếu EC2 ở private subnet, app không public được nhưng vẫn internal connected.

13. Auto Scaling — “tự phóng to / thu nhỏ theo traffic”

- Làm gì:
  - Có 1 Auto Scaling Group.
  - Khi CPU/load tăng, thêm EC2 instance.
  - Khi traffic giảm, giảm instance.

- Tạo trên AWS:
  - EC2 → Auto Scaling Groups → Create
  - Chọn launch template
  - Set min/max/desired capacity
  - Chọn target tracking policy, ví dụ CPU > 60%

- Ảnh hưởng:
  - Giúp app chịu tải tốt hơn.
  - Tự động cập nhật mà không cần thao tác thủ công.
  - Làm nền cho “production-ready” nhưng phải có ALB hoặc load balancer phía trước.

14. ALB — “cửa vào ứng dụng”

- Làm gì:
  - ALB (Application Load Balancer) đi trước nhiều EC2 hoặc target group.
  - Gửi request phân tán sang các instance.

- Tạo trên AWS:
  - EC2 → Load Balancers → Create ALB
  - Chọn VPC, subnets, SG
  - Target group gắn EC2 instances
  - Các listener: HTTP/HTTPS đến port 80/443

- Ảnh hưởng:
  - User gọi vào ALB, ALB phân tải tới EC2.
  - Nếu 1 EC2 chết, ALB vẫn chuyển traffic cho các instance còn sống.
  - ALB có thể xử lý health check, TLS termination, sticky session.

15. RDS — “database quản lý bởi AWS”

- Làm gì:
  - RDS là managed PostgreSQL/MySQL/MariaDB/Oracle/SQL Server.
  - Bạn không cần cài DB trên EC2.

- Tạo trên AWS:
  - RDS → Create database
  - Chọn engine, version, instance class
  - Chọn VPC, subnet group, SG, database credentials

- Ảnh hưởng:
  - App chạy trên EC2, DB nằm ở RDS.
  - RDS nên đặt private subnet.
  - Security Group chỉ cho phép app SG truy cập tới database port.
  - Đây là cách tách compute và data, dễ bảo mật và dễ backup hơn.

16. S3 — “thùng lưu trữ object”

- Làm gì:
  - S3 lưu file, photo, backup, artifact, static content.
  - Dùng cho:
    - upload artifact Docker, jar
    - lưu backup
    - static website

- Tạo trên AWS:
  - S3 → Create bucket
  - Chọn region, versioning, bucket policy

- Ảnh hưởng:
  - EC2 hoặc CI/CD pipeline có thể đọc/ghi S3.
  - Với role attached vào EC2, app có thể lấy artifact hoặc lưu backup mà không cần key.
  - Nếu bạn deploy trên AWS, S3 thường đi cùng với EC2/RDS cho artifact, logging, hoặc storage.

17. CloudWatch — “mắt giám sát”

- Làm gì:
  - CloudWatch theo dõi metrics, log, alarm.
  - Ví dụ:
    - CPU usage
    - memory
    - request count
    - error rate
    - latency

- Tạo trên AWS:
  - CloudWatch → Metrics / Logs / Alarms
  - Gắn metric filter hoặc alarm threshold

- Ảnh hưởng:
  - Bạn biết app có đang healthy không.
  - Nếu CPU vượt ngưỡng, Auto Scaling có thể scale up.
  - Nếu error quá nhiều, alarm sẽ notify.

18. Mối liên hệ “affect” của chúng với nhau

Giả sử bạn xây hệ thống giống:
- Internet
  -> ALB
  -> EC2 (public subnet)
  -> RDS (private subnet)
  -> S3 (storage)
  -> CloudWatch (monitoring)

Thì ảnh hưởng của các khái niệm là:

- VPC quyết định mọi resource có thể ở cùng mạng không.
- CIDR quyết định không gian IP bị cấp cho subnet.
- Subnet quyết định resource public/private.
- Route table quyết định subnet đi ra Internet bằng cách nào.
- IGW và NAT quyết định public/private subnet có truy cập Internet không.
- Security Group quyết định ai được vào instance.
- IAM Role/Policy quyết định resource có quyền làm gì.
- EC2 là nơi app chạy.
- ALB nằm trước EC2 để cân bằng tải.
- RDS là nơi dữ liệu chạy, đặt ở private subnet.
- S3 lưu file ngoài dữ liệu chính.
- CloudWatch theo dõi health và bảo trì.

Nếu bạn muốn nhớ theo kiểu “sơ đồ hành động”, hãy nhớ câu này:

“VPC dựng mạng → Subnet chia mạng → Route table chỉ đường → IGW/NAT cho ra vào Internet → SG/NACL khóa port → IAM cấp quyền → EC2 chạy app → ALB phân tải → RDS lưu data → S3 lưu object → CloudWatch giám sát.”

19. Cách học nhanh nhất trong tuần 2

Bạn không cần học thuộc từng UI như máy móc. Hãy học theo 3 lớp:

- Lớp 1: Mạng
  - VPC, subnet, route table, IGW, NAT

- Lớp 2: Identity & access
  - IAM User, Role, Policy

- Lớp 3: Compute + data
  - EC2, Auto Scaling, ALB, RDS, S3, CloudWatch

20. Câu hỏi thường gặp khi bạn thực hành

- “Tại sao RDS lại đặt private subnet?”
  - Vì DB không nên public.

- “Tại sao EC2 lại thường ở public subnet?”
  - Vì app cần expose hoặc có ALB phía trước.

- “Tại sao không dùng root account?”
  - Vì root có quyền vô hạn, an toàn kém.

- “Role khác User ở chỗ nào?”
  - User là “con người / account”, Role là “quyền được gắn cho resource”.

- “Security Group khác NACL gì?”
  - SG = instance-level firewall
  - NACL = subnet-level firewall

Nếu bạn muốn, tôi có thể làm tiếp cho bạn một phiên bản “mỗi AWS service trên tuần 2 giải thích bằng ví dụ 1 project cụ thể” theo kiểu:
- Internet → ALB → EC2 → RDS → S3
- Và bạn sẽ biết rõ từng service “được tạo từ đâu, dùng như thế nào, tại sao lại cần nó”.