# Store listing — Bahasa Indonesia (id-ID)

The app ships `values-in` strings, so the listing should be localised too. Add this as a second
language in the Play Console under Store presence → Main store listing.

## App name (30 chars max)

```
Family Group: Obrolan & Peta
```

(28 characters.)

## Short description (80 chars max)

```
Obrolan grup terenkripsi dan peta langsung di broker MQTT Anda sendiri.
```

(70 characters.)

## Full description (4000 chars max)

```
Family Group adalah aplikasi obrolan dan lokasi untuk sekelompok kecil orang yang sudah saling
percaya — satu keluarga, satu rumah, beberapa teman. Tanpa akun, tanpa iklan, dan tanpa server
milik kami di mana pun.

Aplikasi ini terhubung ke broker MQTT pilihan Anda sendiri. Arahkan ke broker publik untuk
mencoba, atau jalankan Mosquitto maupun EMQX di mesin milik Anda — sehingga tidak ada bagian
dari grup Anda yang menyentuh infrastruktur orang lain.

<b>Tidak ada yang bisa membaca pesan Anda, termasuk broker</b>
Setiap pesan, foto, pesan suara, dan posisi dienkripsi di ponsel Anda dengan kunci yang hanya
ada di ponsel anggota grup. Broker hanya memindahkan data tersegel yang tidak bisa ia buka.
Kuncinya dibuat saat Anda membuat grup dan hanya keluar di dalam kode QR undangan, yang
ditunjukkan langsung secara tatap muka.

<b>Tanpa akun</b>
Tidak ada pendaftaran dan tidak ada nomor telepon yang perlu diberikan. Identitas Anda adalah id
acak yang dibuat saat pertama dijalankan. Anda bergabung dengan memindai kode dari seseorang
yang sudah ada di dalam grup.

<b>Obrolan</b>
Teks, foto, dan pesan suara, lengkap dengan status terkirim dan dibaca. Pesan yang
dikirim saat offline akan terkirim begitu koneksi kembali.

<b>Peta, jika grup memang menginginkannya</b>
Berbagi lokasi mati sampai admin grup menyalakannya, dan setelah itu pun tetap mati untuk Anda
sampai Anda sendiri yang menyalakannya — tidak ada orang lain yang bisa menyalakannya untuk
Anda. Saat aktif, peta menunjukkan posisi semua orang, dengan riwayat yang bisa diputar ulang:
pilih rentang tanggal, saksikan jalur perjalanan grup tergambar sendiri, dan hapus riwayat siapa
pun dari ponsel Anda kapan saja.

Admin mengatur seberapa sering setiap ponsel mengambil posisi dan seberapa sering mengirimnya,
sehingga grup bisa memilih antara peta yang rinci atau baterai yang lebih awet.

<b>Data Anda tetap di ponsel Anda</b>
Pesan, foto, dan posisi disimpan dalam basis data terenkripsi di perangkat, tanpa salinan di
cloud. Tersedia berkas cadangan terenkripsi berfrasa sandi untuk pindah ke ponsel baru.

<b>Lebih dari satu broker</b>
Setiap grup menentukan brokernya sendiri, sehingga keluarga di server rumah dan teman-teman di
broker publik bisa berdampingan tanpa server yang satu melihat yang lain.

<b>Izin, dan alasannya</b>
- Internet: hanya untuk menghubungi broker yang Anda konfigurasikan sendiri.
- Lokasi: hanya jika grup mengaktifkan peta dan Anda memilih berbagi. Posisi dikirim terenkripsi
  ke grup Anda melalui broker Anda. Berbagi tetap berjalan saat aplikasi ditutup, dengan
  notifikasi yang tampil selama itu berlangsung.
- Kamera: memindai kode QR undangan dan mengambil foto.
- Mikrofon: merekam pesan suara.
- Notifikasi: memberi tahu ada pesan masuk.
- Berjalan saat perangkat menyala dan pengecualian baterai: agar pesan tetap sampai. Opsional.

<b>Yang bukan aplikasi ini</b>
Ini bukan pengganti aplikasi pesan dengan jutaan pengguna. Tidak ada penemuan kontak, tidak ada
pencarian lewat nomor telepon, dan tidak ada grup yang jauh lebih besar dari selusin orang. Ini
dibuat untuk grup yang cukup kecil sehingga semua orang bisa memindai satu kode QR di ruangan
yang sama.

<b>Arah pengembangan berikutnya</b>
Aplikasi ini adalah purwarupa yang berfungsi penuh dari sebuah komunikator privat untuk sistem
tertutup perusahaan — lokasi tambang, perkebunan, kapal, pabrik, atau proyek konstruksi: tempat
di mana jaringan adalah milik Anda sendiri, internet luar tidak dapat diandalkan atau memang
sengaja ditiadakan, dan pesan maupun posisi kru tidak boleh keluar dari infrastruktur operator.
Rancangannya tetap sama: broker MQTT yang Anda kelola sendiri, enkripsi ujung-ke-ujung yang
tidak bisa dibuka oleh broker, tanpa akun, dan peta yang tetap berfungsi saat koneksi tidak.

Jika Anda sedang membangun sistem serupa, atau ingin aplikasi ini disesuaikan dengan operasi
Anda, silakan hubungi kami — tombol Kontak di halaman Tentang pada aplikasi akan membuka
formulir permintaan.
```

## Catatan

The console answers (category, data safety, sensitive permissions) are the same for both
languages — they are set once per app, not per listing. See `en-US.md`.
