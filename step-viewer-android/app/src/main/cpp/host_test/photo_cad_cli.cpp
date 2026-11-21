// Fotoğraftan model çekirdeğini dosyadan okunan taslaklarla çalıştırır (masaüstü denemesi).
// Kullanım: photo_cad_cli mod(0|1|2) a.txt b.txt|- c.txt|- param eksen bayrak cikti.step
// Taslak dosyası: DecodeSketch biçiminde boşlukla ayrılmış sayılar.
#include "../photo_cad.h"

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>
#include <vector>

using namespace stepviewer;

static Sketch Load(const char* path) {
  Sketch s;
  if (std::string(path) == "-") return s;
  std::ifstream in(path);
  std::vector<double> v;
  double d;
  while (in >> d) v.push_back(d);
  DecodeSketch(v.data(), v.size(), s);
  return s;
}

int main(int argc, char** argv) {
  if (argc < 9) {
    std::fprintf(stderr, "Kullanim: %s mod a b c param eksen bayrak cikti.step\n", argv[0]);
    return 2;
  }
  PhotoCadResult r = BuildPhotoSolid(static_cast<PhotoCadMode>(std::atoi(argv[1])), Load(argv[2]),
                                     Load(argv[3]), std::atof(argv[5]), std::atoi(argv[6]), argv[8],
                                     "parca", Load(argv[4]), std::atoi(argv[7]));
  if (!r.ok) {
    std::printf("HATA %s\n", r.error.c_str());
    return 1;
  }
  std::printf("hacim=%.1f boyut=%.2f x %.2f x %.2f\n", r.volume, r.size[0], r.size[1], r.size[2]);
  return 0;
}
