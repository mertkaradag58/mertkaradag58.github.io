// Mesh (üçgen) dosyalarını uygulamanın model yapısına (MeshData) çevirir.
//
// Mesh dosyalarında CAD'deki gibi "yüzey" ve "kenar" bilgisi yoktur; yalnızca üçgenler vardır.
// Burada üçgenler düz bölgelere ve yumuşak (eğri) bölgelere ayrılır, bölge sınırlarından
// kenar çizgileri çıkarılır. Böylece seçme, ölçme ve kenar gösterimi STEP'teki gibi çalışır.
#pragma once

#include <array>

#include "step_mesher.h"

namespace stepviewer {

// İçe aktarıcının (Assimp) ürettiği ham parça: dünya koordinatlarında, mm.
struct RawPart {
  std::vector<float> positions;      // xyz
  std::vector<uint32_t> indices;     // üçgenler
  std::vector<uint32_t> triMaterial; // üçgen başına renk indeksi (boşsa hepsi 0)
};

struct RawMesh {
  std::vector<RawPart> parts;
  // Montaj ağacı; yaprakların leafStart değeri parça indeksidir. leafEnd burada hesaplanır.
  std::vector<TreeNode> tree;
  std::vector<std::array<float, 3>> colors;  // sRGB renkler (boşsa varsayılan gri)
};

// Dosyayı Assimp ile okur (mesh_import.cpp).
bool ImportMeshFile(const std::string& path, FileFormat format, RawMesh& out, std::string& error);

// Ham mesh'i bölgelere ayırır, kenarları çıkarır ve `out`u doldurur. `raw` boşaltılır.
bool BuildMeshModel(RawMesh& raw, MeshData& out, std::string& error);

}  // namespace stepviewer
