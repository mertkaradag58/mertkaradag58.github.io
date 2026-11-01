// Masaüstü testi: step_mesher çekirdeğini Android olmadan dener.
// Kullanım: step_host_test dosya.step [cikti.obj] [--info] [--measure i j]...
#include "../step_mesher.h"

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>

static const char* TypeName(stepviewer::EdgeType t) {
  switch (t) {
    case stepviewer::EdgeType::Line: return "dogru";
    case stepviewer::EdgeType::Circle: return "cember";
    case stepviewer::EdgeType::Ellipse: return "elips";
    case stepviewer::EdgeType::BSpline: return "bspline";
    default: return "diger";
  }
}

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

  std::printf("kenar_sayisi=%zu (offset=%zu)\n", mesh.edgeShapes.size(), mesh.edgeOffsets.size());
  for (int a = 2; a < argc; ++a) {
    if (std::strcmp(argv[a], "--info") == 0) {
      for (size_t e = 0; e < mesh.edgeShapes.size(); ++e) {
        stepviewer::EdgeInfo info;
        if (!stepviewer::GetEdgeInfo(mesh.edgeShapes[e], info)) continue;
        std::printf("kenar %zu: %s L=%.4f R=%.4f merkez=(%.3f %.3f %.3f) seg=%d..%d\n", e,
                    TypeName(info.type), info.length, info.radius, info.center[0],
                    info.center[1], info.center[2], mesh.edgeOffsets[e], mesh.edgeOffsets[e + 1]);
      }
    } else if (std::strcmp(argv[a], "--measure") == 0 && a + 2 < argc) {
      const size_t i = std::strtoul(argv[a + 1], nullptr, 10);
      const size_t j = std::strtoul(argv[a + 2], nullptr, 10);
      a += 2;
      stepviewer::EdgeDistance d;
      if (i < mesh.edgeShapes.size() && j < mesh.edgeShapes.size() &&
          stepviewer::MeasureEdges(mesh.edgeShapes[i], mesh.edgeShapes[j], d)) {
        std::printf("olcum %zu-%zu: mesafe=%.4f p1=(%.3f %.3f %.3f) p2=(%.3f %.3f %.3f) aci=%.2f merkez_mesafe=%.4f\n",
                    i, j, d.distance, d.p1[0], d.p1[1], d.p1[2], d.p2[0], d.p2[1], d.p2[2],
                    d.angleDeg, d.centerDistance);
      } else {
        std::printf("olcum %zu-%zu: basarisiz\n", i, j);
      }
    }
  }

  if (argc >= 3 && argv[2][0] != '-') {
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
