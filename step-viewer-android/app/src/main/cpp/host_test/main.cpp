// Masaüstü testi: step_mesher çekirdeğini Android olmadan dener.
// Kullanım: step_host_test dosya.step [cikti.obj]
#include "../step_mesher.h"

#include <chrono>
#include <cstdio>

int main(int argc, char** argv) {
  if (argc < 2) {
    std::fprintf(stderr, "Kullanim: %s dosya.step [cikti.obj]\n", argv[0]);
    return 2;
  }
  stepviewer::MeshData mesh;
  std::string error;
  const auto t0 = std::chrono::steady_clock::now();
  if (!stepviewer::LoadStepFile(argv[1], stepviewer::MeshOptions(), mesh, error)) {
    std::fprintf(stderr, "HATA: %s\n", error.c_str());
    return 1;
  }
  const double ms = std::chrono::duration<double, std::milli>(
                        std::chrono::steady_clock::now() - t0).count();
  std::printf("parca=%d yuzey=%d kose=%zu ucgen=%zu kenar_segment=%zu sure=%.0fms\n",
              mesh.partCount, mesh.faceCount, mesh.vertexCount(), mesh.triangleCount(),
              mesh.edges.size() / 6, ms);
  std::printf("bbox=(%.3f %.3f %.3f)-(%.3f %.3f %.3f)\n", mesh.bboxMin[0], mesh.bboxMin[1],
              mesh.bboxMin[2], mesh.bboxMax[0], mesh.bboxMax[1], mesh.bboxMax[2]);

  if (argc >= 3) {
    FILE* f = std::fopen(argv[2], "w");
    if (!f) return 1;
    const int k = stepviewer::kFloatsPerVertex;
    for (size_t i = 0; i < mesh.vertexCount(); ++i) {
      const float* v = &mesh.vertices[i * k];
      std::fprintf(f, "v %f %f %f %f %f %f\n", v[0], v[1], v[2], v[6], v[7], v[8]);
    }
    for (size_t i = 0; i < mesh.vertexCount(); ++i) {
      const float* v = &mesh.vertices[i * k];
      std::fprintf(f, "vn %f %f %f\n", v[3], v[4], v[5]);
    }
    for (size_t t = 0; t < mesh.triangleCount(); ++t) {
      const unsigned a = mesh.indices[t * 3] + 1, b = mesh.indices[t * 3 + 1] + 1,
                     c = mesh.indices[t * 3 + 2] + 1;
      std::fprintf(f, "f %u//%u %u//%u %u//%u\n", a, a, b, b, c, c);
    }
    std::fclose(f);
  }
  return 0;
}
