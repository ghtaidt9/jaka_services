CIDR: Classless inter - Domain routing: define a range of ip addresses
cidrhost(var.vpc.cidr, 0): returns the first address  in the range
    Call	Result
    cidrhost("10.0.0.0/16", 0)	"10.0.0.0" (1st address)
    cidrhost("10.0.0.0/16", 5)	"10.0.0.5" (6th address)
    cidrhost("10.0.0.0", 0)	error: no /prefix
    cidrhost("hello", 0)	error: not an IP
can(...) turns error into false, success into true



Replication factor	min.insync.replicas	1 broker chết	Kết quả
3	2	ISR còn 2 ≥ 2	✅ Ghi thành công
2	2	ISR còn 1 < 2	❌ Từ chối ghi: NotEnoughReplicasException
2	1	ISR còn 1 ≥ 1	✅ Ghi được, nhưng chỉ còn 1 bản; broker này chết nữa là mất dữ liệu
1 (kind của bạn)	1	Không còn broker	❌ Không ghi được gì
Cấu hình chuẩn là RF=3, min.insync=2: chịu được 1 broker chết mà vẫn ghi được và vẫn có 2 bản.
(ISR: in-sync-replicas - Tap cac replicas cua mot partition dang theo kip leader)

kafka.send(record).join()
  ├─ producer tự retry bên trong (tối đa delivery.timeout.ms, mặc định 2 phút)
  └─ vẫn lỗi → join() ném exception
       → không chạy UPDATE outbox SET published_at
       → @Transactional rollback, dòng outbox vẫn published_at = NULL
       → 1 giây sau relay quét lại, gửi lại

Mình đã sửa (chỉ trong tài khoản của bạn)

- Xoá nguồn nhập Bamboo trong GNOME, chỉ giữ bàn phím US.
- Xoá thư mục ~/.config/ibus.
- Tạo ~/.config/environment.d/90-fcitx5.conf để cả phiên dùng fcitx5 (GTK_IM_MODULE, QT_IM_MODULE(S), XMODIFIERS).
- Telegram (bản flatpak): đặt flatpak override để dùng fcitx.
- Chrome: tạo ~/.local/share/applications/google-chrome.desktop để chạy với --gtk-version=4, chế độ cho phép fcitx5 gõ được trên Wayland.
- Tạo ~/.config/fcitx5/profile với hai bộ gõ US và Unikey.


The infrastructure built in the previous stage, what have been replaced by what in the current infrastructure?
How to run some services locally and able to commute with others on the dev infrastructure
How to use local tool connect db on statefullset there?
How to create account grafana and jeager for memmers?