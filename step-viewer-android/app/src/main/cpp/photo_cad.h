// Fotoğraftan kaba katı model: kullanıcının fotoğraf üzerinde çıkardığı/düzelttiği 2B
// konturlardan (mm) OCCT ile katı oluşturur ve STEP olarak yazar.
//
// Yöntemler:
//   Ekstrüzyon  : tek görünüş (üstten), verilen kalınlık kadar çekilir.
//   İki görünüş : üst görünüş Z yönünde, ön görünüş Y yönünde çekilip kesişimi alınır.
//   Döndürme    : yan görünüşün eksenin bir yanındaki yarısı eksen etrafında döndürülür.
#pragma once

#include <string>
#include <vector>

namespace stepviewer {

struct SketchLoop {
  bool circle = false;
  double cx = 0, cy = 0, r = 0;      // daire
  std::vector<double> pts;           // çokgen: x0, y0, x1, y1, ... (mm, y yukarı)
};

// İlk döngü dış kontur, diğerleri delik.
using Sketch = std::vector<SketchLoop>;

// Kotlin'den gelen düz dizi: [döngü sayısı, (tür 0: n, x0, y0, ... | tür 1: cx, cy, r)...]
bool DecodeSketch(const double* data, size_t count, Sketch& out);

enum class PhotoCadMode { Extrude = 0, TwoView = 1, Revolve = 2 };

struct PhotoCadResult {
  bool ok = false;
  std::string error;   // open_profile, bad_profile, build_failed, empty_result, write_failed
  double volume = 0;   // mm³
  double size[3] = {0, 0, 0};
};

// Görünüş yönü bayrakları (çok görünüşte)
constexpr int kFlipTop = 1;    // üst görünüşte parçanın önü resmin üstünde (ISO E teknik resim)
constexpr int kSideRight = 2;  // yan görünüş sağdan bakış (varsayılan: soldan bakış)

// a: ana görünüş (ekstrüzyon: üst, çok görünüş: üst, döndürme: yan), b: çok görünüşte ön,
// c: çok görünüşte isteğe bağlı yan görünüş (boş olabilir).
// param: ekstrüzyon kalınlığı (mm). axis: döndürmede 0 = yatay eksen, 1 = dikey eksen.
PhotoCadResult BuildPhotoSolid(PhotoCadMode mode, const Sketch& a, const Sketch& b, double param,
                               int axis, const std::string& outPath, const std::string& name,
                               const Sketch& c = Sketch(), int flags = 0);

}  // namespace stepviewer
