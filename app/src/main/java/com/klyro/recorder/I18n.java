package com.klyro.recorder;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class I18n {
    private static final String[] LANGS = {"vi","en","es","fr","ja","ko","zh","th","id"};
    private static final Map<String,String[]> WORDS = new HashMap<>();
    static {
        put("app","Klyro Screen Studio","Klyro Screen Studio","Klyro Screen Studio","Klyro Screen Studio","Klyro Screen Studio","Klyro Screen Studio","Klyro 屏幕录制","Klyro Screen Studio","Klyro Screen Studio");
        put("home","Ghi hình","Record","Grabar","Enregistrer","録画","녹화","录制","บันทึก","Rekam");
        put("history","Lịch sử","History","Historial","Historique","履歴","기록","历史","ประวัติ","Riwayat");
        put("settings","Cài đặt","Settings","Ajustes","Réglages","設定","설정","设置","ตั้งค่า","Setelan");
        put("touch","Dấu chạm","Touch marks","Toques","Touches","タップ表示","터치 표시","触摸标记","ตัวบ่งชี้แตะ","Penanda sentuh");
        put("ready","Sẵn sàng ghi","Ready to record","Listo para grabar","Prêt à enregistrer","録画の準備完了","녹화 준비 완료","准备录制","พร้อมบันทึก","Siap merekam");
        put("video","Video màn hình","Screen video","Vídeo de pantalla","Vidéo d’écran","画面録画","화면 녹화","屏幕视频","วิดีโอหน้าจอ","Video layar");
        put("audio_only","Chỉ âm thanh","Audio only","Solo audio","Audio uniquement","音声のみ","오디오만","仅音频","เสียงเท่านั้น","Audio saja");
        put("microphone","Micro","Microphone","Micrófono","Microphone","マイク","마이크","麦克风","ไมโครโฟน","Mikrofon");
        put("countdown","Đếm ngược 3 giây","3-second countdown","Cuenta atrás de 3 s","Compte à rebours 3 s","3秒カウントダウン","3초 카운트다운","3秒倒计时","นับถอยหลัง 3 วินาที","Hitung mundur 3 detik");
        put("floating","Bóng điều khiển nổi","Floating controls","Controles flotantes","Commandes flottantes","フローティング操作","플로팅 컨트롤","悬浮控制","ปุ่มลอย","Kontrol mengambang");
        put("start","Bắt đầu ghi","Start recording","Iniciar grabación","Démarrer","録画開始","녹화 시작","开始录制","เริ่มบันทึก","Mulai rekam");
        put("pause","Tạm dừng","Pause","Pausar","Pause","一時停止","일시정지","暂停","หยุดชั่วคราว","Jeda");
        put("resume","Tiếp tục","Resume","Reanudar","Reprendre","再開","재개","继续","ทำต่อ","Lanjutkan");
        put("stop","Kết thúc","Stop","Detener","Arrêter","停止","중지","停止","หยุด","Berhenti");
        put("resolution","Độ phân giải","Resolution","Resolución","Résolution","解像度","해상도","分辨率","ความละเอียด","Resolusi");
        put("fps","Tốc độ khung hình","Frame rate","Fotogramas","Images/s","フレームレート","프레임 속도","帧率","อัตราเฟรม","Frame rate");
        put("bitrate","Bitrate / dung lượng","Bitrate / file size","Bitrate / tamaño","Débit / taille","ビットレート","비트레이트","码率 / 文件大小","บิตเรต / ขนาดไฟล์","Bitrate / ukuran");
        put("language","Ngôn ngữ","Language","Idioma","Langue","言語","언어","语言","ภาษา","Bahasa");
        put("system_language","Tự động theo máy","Follow device","Automático","Automatique","端末に合わせる","기기 언어","跟随系统","ตามภาษาเครื่อง","Ikuti perangkat");
        put("saved","Đã lưu video","Video saved","Vídeo guardado","Vidéo enregistrée","動画を保存しました","동영상 저장됨","视频已保存","บันทึกวิดีโอแล้ว","Video tersimpan");
        put("empty_history","Chưa có bản ghi nào","No recordings yet","Aún no hay grabaciones","Aucun enregistrement","録画はまだありません","녹화가 없습니다","暂无录制","ยังไม่มีรายการบันทึก","Belum ada rekaman");
        put("tap_enable","Bật dấu chạm tuỳ chỉnh","Enable custom tap marker","Activar marca de toque","Activer les touches","タップマーカーを有効化","터치 표시 켜기","启用自定义触摸标记","เปิดตัวบ่งชี้แตะ","Aktifkan penanda sentuh");
        put("accessibility","Mở cài đặt Trợ năng","Open Accessibility settings","Abrir Accesibilidad","Ouvrir Accessibilité","ユーザー補助を開く","접근성 설정 열기","打开无障碍设置","เปิดการช่วยเหลือพิเศษ","Buka Aksesibilitas");
        put("developer_taps","Show taps của Android","Android Show taps","Mostrar toques de Android","Afficher les touches Android","Androidのタップ表示","Android 터치 표시","Android 显示触摸","แสดงการแตะของ Android","Tampilkan sentuhan Android");
        put("size","Kích thước","Size","Tamaño","Taille","サイズ","크기","大小","ขนาด","Ukuran");
        put("opacity","Độ trong suốt","Opacity","Opacidad","Opacité","不透明度","불투명도","不透明度","ความทึบ","Opasitas");
        put("effect","Hiệu ứng","Effect","Efecto","Effet","エフェクト","효과","效果","เอฟเฟกต์","Efek");
        put("image","Ảnh tuỳ chỉnh","Custom image","Imagen propia","Image perso","カスタム画像","사용자 이미지","自定义图片","ภาพกำหนดเอง","Gambar kustom");
        put("preview","Xem trước","Preview","Vista previa","Aperçu","プレビュー","미리보기","预览","ตัวอย่าง","Pratinjau");
        put("permission","Quyền cần thiết","Required permissions","Permisos necesarios","Autorisations","必要な権限","필수 권한","所需权限","สิทธิ์ที่จำเป็น","Izin yang diperlukan");
        put("audio_note","Âm thanh đang dùng micro của máy. Âm thanh nội bộ tuỳ ứng dụng và phiên bản Android; bản này chưa trộn âm thanh hệ thống.","Audio uses the device microphone. Internal audio depends on the app and Android version; this build does not mix system audio.","El audio usa el micrófono. El audio interno depende de la app y Android; esta versión no mezcla el audio del sistema.","L’audio utilise le microphone. L’audio interne dépend de l’application et d’Android; cette version ne mélange pas le son système.","音声は端末のマイクを使用します。内部音声はアプリとAndroidに依存し、この版ではシステム音声をミックスしません。","오디오는 기기 마이크를 사용합니다. 내부 오디오는 앱과 Android에 따라 다르며 이 버전은 시스템 오디오를 믹스하지 않습니다.","音频使用设备麦克风。内部音频取决于应用和 Android 版本；此版本不混合系统音频。","เสียงใช้ไมโครโฟนของเครื่อง เสียงภายในขึ้นกับแอปและ Android รุ่นนี้ยังไม่ผสมเสียงระบบ","Audio memakai mikrofon perangkat. Audio internal bergantung pada aplikasi dan Android; versi ini belum mencampur audio sistem.");
    }
        put("audio_app","Âm thanh ứng dụng","App audio","Audio de la aplicación","Audio de l’application","アプリの音声","앱 오디오","应用音频","เสียงจากแอป","Audio aplikasi");
        put("audio_mic","Micro điện thoại","Phone microphone","Micrófono del teléfono","Microphone du téléphone","端末のマイク","휴대전화 마이크","手机麦克风","ไมโครโฟนโทรศัพท์","Mikrofon ponsel");
        put("audio_capture_note","Chế độ này không bật micro. Android sẽ hỏi quyền thu âm thanh; chỉ âm thanh của ứng dụng cho phép ghi và không được bảo vệ mới thu được. Một số game/ứng dụng có thể cho ra âm thanh im lặng.","This mode does not use the microphone. Android asks for capture permission; only playback from apps that allow capture and is not protected can be recorded. Some apps/games may remain silent.","Este modo no usa el micrófono. Android solicita permiso; solo se graba el audio de aplicaciones que permiten la captura y no está protegido. Algunas apps o juegos pueden quedar en silencio.","Ce mode n’utilise pas le microphone. Android demande l’autorisation; seules les applications qui autorisent la capture et dont le son n’est pas protégé peuvent être enregistrées. Certaines apps peuvent rester silencieuses.","このモードはマイクを使いません。Androidの許可が必要です。録音を許可し、保護されていないアプリ音声のみ取得できます。一部のアプリやゲームは無音になる場合があります。","이 모드는 마이크를 사용하지 않습니다. Android 권한이 필요하며 캡처를 허용하고 보호되지 않은 앱 소리만 녹음됩니다. 일부 앱이나 게임은 무음일 수 있습니다.","此模式不使用麦克风。Android 会请求录制权限；只能录制允许捕获且未受保护的应用播放音频。部分应用或游戏可能没有声音。","โหมดนี้ไม่ใช้ไมโครโฟน Android จะขออนุญาตบันทึก เฉพาะเสียงจากแอปที่อนุญาตและไม่มีการป้องกันเท่านั้น บางแอปหรือเกมอาจไม่มีเสียง","Mode ini tidak menggunakan mikrofon. Android meminta izin; hanya audio aplikasi yang mengizinkan penangkapan dan tidak dilindungi yang dapat direkam. Beberapa aplikasi/game mungkin senyap.");
    private static void put(String key, String... values) { WORDS.put(key, values); }
    private I18n() {}
    public static String language(Context c) {
        SharedPreferences p = c.getSharedPreferences("klyro", Context.MODE_PRIVATE);
        String selected = p.getString("language", "system");
        if (!"system".equals(selected)) return selected;
        String system = Locale.getDefault().getLanguage();
        for (String l : LANGS) if (l.equals(system)) return system;
        return "en";
    }
    public static String t(Context c, String key) {
        String[] values = WORDS.get(key);
        if (values == null) return key;
        String lang = language(c);
        for (int i = 0; i < LANGS.length; i++) if (LANGS[i].equals(lang)) return values[Math.min(i, values.length - 1)];
        return values[1];
    }
    public static String[] languageLabels() {
        return new String[]{"Tự động theo máy","Tiếng Việt","English","Español","Français","日本語","한국어","简体中文","ไทย","Bahasa Indonesia"};
    }
    public static String[] languageCodes() {
        return new String[]{"system","vi","en","es","fr","ja","ko","zh","th","id"};
    }
}
