# Cepte CAD – STEP Görüntüleyici (Android)

STEP (`.step` / `.stp`), IGES (`.igs` / `.iges`) ve BREP (`.brep`) dosyalarını telefonda **tamamen çevrimdışı** açıp 3B olarak gösteren yerel (native) Android uygulaması.

- **Geometri çekirdeği:** [Open CASCADE Technology (OCCT)](https://dev.opencascade.org/) 7.8.1, Android NDK ile statik derlenir
- **Köprü:** JNI (C++ → Kotlin)
- **Çizim:** OpenGL ES 3.0, `GLSurfaceView`, 4x MSAA
- **Arayüz:** Kotlin, Material 3 (koyu tema, kenardan kenara)

## Özellikler (v1.0)

**Dosya**
- Dosya seçiciden veya dosya yöneticisinde **"Birlikte aç"** ile STEP / IGES / BREP açma
- Son açılan dosyalar listesi (açılış ekranında)
- Montajlar: parça yerleşimleri ve STEP renkleri doğru uygulanır

**Görünüm**
- Tek parmak döndür (döner tabla mantığı, model yan yatmaz), bırakınca ataletle devam eder
- İki parmak yakınlaştır / kaydır
- **Çift dokunma: modeli 360° döndürür**
- Standart görünümler (ön, arka, üst, alt, sol, sağ, izometrik) yumuşak geçişle
- Perspektif / ortografik, Z-yukarı / Y-yukarı
- Görüntü modu: gölgeli + kenarlar, sadece gölgeli, tel kafes
- **Kesit görünümü:** X / Y / Z düzlemi, kaydırıcı ile konum, yön çevirme; kesilen iç yüzeyler ayrı renkte

**Mühendislik**
- **Ölçüm:** kenar veya yüzeye dokun
  - Tek seçim: kenar uzunluğu, çember çapı, düzlem alanı, silindir/delik çapı, koni açısı, küre çapı
  - İki seçim: en kısa mesafe (`BRepExtrema`, tam geometri), ΔX/ΔY/ΔZ, açı, paralel düzlemler arası mesafe,
    çember merkezleri / eksenler arası mesafe
- **Parça seçme:** "Seç" düğmesi veya herhangi bir modda uzun basma ile modelde parçaya dokun;
  adı ve montaj yolu görünür, tek dokunuşla gizle / yalnız bunu göster / parça bilgisi (boyut, hacim, kütle)
- **Parça ağacı:** ada göre arama, gizle / göster, tek parçayı izole et, seçili parça listede işaretli
- Gizli parça varken "Geri al" ve "Tümünü göster" kısayolları
- **Model bilgisi:** boyutlar, hacim, yüzey alanı, kütle merkezi; malzeme seçimi veya özel yoğunluk ile kütle
- **Ekran görüntüsü paylaşma** (ölçüm sonucu ve dosya adı resmin üstüne yazılır)
- **Reklamlar:** Google AdMob, altta uyarlanabilir banner, dosya açılınca ve paylaşımdan sonra geçiş reklamı (en az 60 sn arayla), Avrupa için UMP onay formu. Kimlikler `app/build.gradle.kts` içinde (verilmezse test reklamları)
- **İki dil:** telefon Türkçeyse Türkçe, değilse İngilizce (`res/values-tr` ve `res/values`); yerel çekirdek hata kodu döner, metin Kotlin tarafında çevrilir

Sonra: PMI / GD&T (STEP AP242 içindeki toleranslar) ayrı bir iş olarak planlandı.

## Mimari

```
Kotlin (MainActivity)
  │  dosyayı önbelleğe kopyala, arka planda yükle
  ▼
StepNative.loadStep(path)            ── JNI ──►  jni_bridge.cpp
                                                   │
                                                   ▼
                                                 step_mesher.cpp
                                                   1. STEPCAFControl_Reader → XCAF belge
                                                   2. BRepMesh_IncrementalMesh (paralel)
                                                   3. XCAFPrs_DocumentExplorer + RWMesh_FaceIterator
                                                      → köşe (konum, normal, renk) + indeks + kenarlar
  ◄── StepMesh (FloatArray / IntArray) ──
  │
  ▼
ModelView (GLSurfaceView) + ModelRenderer (ES 3.0) + Camera
```

| Dosya | Görev |
|---|---|
| `scripts/build_occt_android.sh` | OCCT'yi NDK ile derler (yalnızca gereken araç takımları) |
| `app/src/main/cpp/step_mesher.*` | STEP okuma ve üçgenleme (JNI'den bağımsız) |
| `app/src/main/cpp/jni_bridge.cpp` | Kotlin ↔ C++ köprüsü |
| `app/src/main/cpp/host_test/` | Çekirdeği masaüstünde (Linux) test etmek için küçük program |
| `app/src/main/java/.../ModelRenderer.kt` | Shader'lar ve çizim |
| `app/src/main/java/.../Camera.kt` | Döndürme / yakınlaştırma / kaydırma matematiği |

## Derleme

### En kolayı: GitHub Actions

Bu klasörde bir değişiklik push edildiğinde `.github/workflows/step-viewer-android.yml` çalışır:
OCCT'yi derler (ilk sefer ~30-40 dk, sonra önbellekten gelir) ve APK'yı **Artifacts** altına `step-viewer-apk` olarak koyar.
Actions sekmesinden **Run workflow** ile elle de başlatılabilir.

### Yerelde

Gerekenler: Android SDK, NDK `27.2.12479018`, CMake 3.22+, Ninja, JDK 17.

```bash
cd step-viewer-android
export ANDROID_NDK_HOME=$ANDROID_SDK_ROOT/ndk/27.2.12479018
./scripts/build_occt_android.sh          # third_party/occt-install içine kurar
./gradlew assembleRelease                # app/build/outputs/apk/release/app-release.apk
```

APK şimdilik debug anahtarıyla imzalanır; doğrudan telefona kurulabilir. Play Store için ayrı bir yükleme anahtarı gerekir.

### Çekirdeği masaüstünde test etmek

```bash
# OCCT'yi masaüstü için aynı ayarlarla derleyip kurduktan sonra:
cmake -S app/src/main/cpp/host_test -B build-host -DOCCT_PREFIX=<occt-kurulum>
cmake --build build-host
./build-host/step_host_test model.step cikti.obj
```

## Lisans notu (OCCT)

OCCT, **LGPL 2.1 + OCCT istisnası** ile lisanslıdır. Uygulama OCCT'yi statik bağladığı için dağıtırken:

- Uygulama içinde (ör. "Hakkında" ekranı) OCCT kullanıldığı ve lisansı belirtilmeli,
- OCCT kaynak kodu (veya değiştirilmişse değişiklikler) erişilebilir olmalı,
- LGPL, kullanıcının kütüphaneyi değiştirip yeniden bağlayabilmesini ister; statik bağlamada bunun için uygulamanın nesne dosyaları veya kaynak kodu sağlanmalıdır. Bu repo açık kaynak olduğu sürece bu koşul karşılanır.

Kapalı kaynak bir sürüm planlanırsa OCCT'yi paylaşımlı (`.so`) kütüphaneler olarak bağlamak daha uygun olur.

## APK boyutu

Yalnızca STEP okuma için gereken OCCT araç takımları derlenir; `-ffunction-sections` + `--gc-sections` ile kullanılmayan kod atılır. Şu an yalnızca `arm64-v8a` (güncel telefonların neredeyse tamamı) hedeflenir.
