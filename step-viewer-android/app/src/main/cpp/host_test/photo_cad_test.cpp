// Fotoğraftan model çekirdeğinin masaüstü testi: üç yöntemle STEP yazar ve geri okur.
// Kullanım: photo_cad_test cikti_klasoru
#include "../photo_cad.h"
#include "../step_mesher.h"

#include <cmath>
#include <cstdio>
#include <string>

using namespace stepviewer;

static bool Check(const char* label, PhotoCadMode mode, const std::vector<double>& a,
                  const std::vector<double>& b, double param, int axis, const std::string& out,
                  double expectVolume) {
  Sketch sa, sb;
  if (!DecodeSketch(a.data(), a.size(), sa)) { std::printf("%s: decode a failed\n", label); return false; }
  if (!b.empty() && !DecodeSketch(b.data(), b.size(), sb)) { std::printf("%s: decode b failed\n", label); return false; }
  PhotoCadResult r = BuildPhotoSolid(mode, sa, sb, param, axis, out, label);
  if (!r.ok) { std::printf("%s: HATA %s\n", label, r.error.c_str()); return false; }
  MeshData mesh;
  std::string err;
  if (!LoadModelFile(out, MeshOptions(), mesh, err)) { std::printf("%s: geri okunamadi %s\n", label, err.c_str()); return false; }
  const double rel = expectVolume > 0 ? std::fabs(r.volume - expectVolume) / expectVolume : 0;
  std::printf("%s: hacim=%.1f (beklenen %.1f, fark %.2f%%) boyut=%.2f x %.2f x %.2f parca=%d yuz=%zu\n",
              label, r.volume, expectVolume, rel * 100, r.size[0], r.size[1], r.size[2],
              mesh.partCount(), mesh.faceShapes.size());
  return rel < 0.02;
}

int main(int argc, char** argv) {
  const std::string dir = argc > 1 ? argv[1] : ".";
  bool ok = true;
  // 1) Plaka: 100x60, sağ üst köşede 20x20 çentik, Ø20 delik, 10 mm kalınlık.
  //    Noktalar resimdeki gibi saat yönünde verilir (y yukarıda negatif).
  std::vector<double> plate = {2,
      0, 6,  0, 0,  0, -60,  100, -60,  100, -20,  80, -20,  80, 0,
      1, 30, -30, 10};
  ok &= Check("plaka", PhotoCadMode::Extrude, plate, {}, 10, 0, dir + "/plaka.step",
              (100 * 60 - 20 * 20 - M_PI * 100) * 10);
  // Kendini kesen kontur hata vermeli
  std::vector<double> bow = {1, 0, 4, 0, 0, 10, 10, 10, 0, 0, 10};
  {
    Sketch s; DecodeSketch(bow.data(), bow.size(), s);
    PhotoCadResult r = BuildPhotoSolid(PhotoCadMode::Extrude, s, {}, 5, 0, dir + "/bow.step", "bow");
    std::printf("kendini kesen: ok=%d hata=%s\n", r.ok, r.error.c_str());
  }
  // 2) İki görünüş: üst 80x40 dikdörtgen, ön L profil (80 genişlik, 50 yükseklik, 30x30 boşluk)
  std::vector<double> top = {1, 0, 4, 0, 0, 80, 0, 80, 40, 0, 40};
  std::vector<double> front = {1, 0, 6, 0, 0, 80, 0, 80, 20, 30, 20, 30, 50, 0, 50};
  ok &= Check("iki_gorunus", PhotoCadMode::TwoView, top, front, 0, 0, dir + "/iki.step",
              (80 * 20 + 30 * 30) * 40.0);
  // 3) Döndürme: kademeli mil yan görünüşü (eksen yatay): Ø40x30 + Ø20x50 (tam siluet)
  std::vector<double> shaft = {1, 0, 8, 0, -20, 30, -20, 30, -10, 80, -10, 80, 10, 30, 10, 30, 20, 0, 20};
  ok &= Check("mil", PhotoCadMode::Revolve, shaft, {}, 0, 0, dir + "/mil.step",
              M_PI * 400 * 30 + M_PI * 100 * 50);
  // 4) Disk: dış daire Ø50, iç Ø20 delik, 5 mm
  std::vector<double> disk = {2, 1, 0, 0, 25, 1, 0, 0, 10};
  ok &= Check("disk", PhotoCadMode::Extrude, disk, {}, 5, 0, dir + "/disk.step",
              M_PI * (625 - 100) * 5);
  // 5) Döndürme dikey eksen: flanş (Ø60x10 + Ø30x20) dikey duran
  std::vector<double> flange = {1, 0, 8, -30, 0, 30, 0, 30, 10, 15, 10, 15, 30, -15, 30, -15, 10, -30, 10};
  ok &= Check("flans", PhotoCadMode::Revolve, flange, {}, 0, 1, dir + "/flans.step",
              M_PI * 900 * 10 + M_PI * 225 * 20);
  std::printf(ok ? "TUMU TAMAM\n" : "BASARISIZ\n");
  return ok ? 0 : 1;
}
