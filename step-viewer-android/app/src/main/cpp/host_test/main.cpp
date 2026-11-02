// Masaüstü testi: çekirdeği Android olmadan dener.
// Kullanım: step_host_test dosya [cikti.obj] [--as-mesh] [--tree] [--info] [--faces]
//           [--measure e|f i e|f j]... [--mass] [--holes]
// --as-mesh: CAD modelinin üçgenlerini STL gibi (köşeleri kaynaşmamış) ham mesh'e çevirip mesh
//            hattından geçirir; yüzey/kenar ayırma ve ölçüm mesh dosyası gibi denenir.
#include "../drawing_views.h"
#include "../dxf_export.h"
#include "../hole_finder.h"
#include "../mesh_model.h"
#include "../step_mesher.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>

using namespace stepviewer;

static const char* KindName(GeomKind k) {
  switch (k) {
    case GeomKind::Line: return "dogru";
    case GeomKind::Circle: return "cember";
    case GeomKind::Ellipse: return "elips";
    case GeomKind::Curve: return "egri";
    case GeomKind::Plane: return "duzlem";
    case GeomKind::Cylinder: return "silindir";
    case GeomKind::Cone: return "koni";
    case GeomKind::Sphere: return "kure";
    case GeomKind::Torus: return "torus";
    case GeomKind::Surface: return "yuzey";
    default: return "diger";
  }
}

static const TopoDS_Shape* Pick(const MeshData& m, const char* kind, size_t id) {
  if (kind[0] == 'e' && id < m.edgeShapes.size()) return &m.edgeShapes[id];
  if (kind[0] == 'f' && id < m.faceShapes.size()) return &m.faceShapes[id];
  return nullptr;
}

static const MeshData* gMesh = nullptr;

static void PrintInfo(const char* label, size_t id, const TopoDS_Shape& s) {
  ShapeInfo i;
  const bool ok = gMesh != nullptr && gMesh->meshGeometry
                      ? MeshFeatureInfo(*gMesh->meshGeometry, label[0] == 'k' ? 0 : 1,
                                        static_cast<int>(id), i)
                      : GetShapeInfo(s, i);
  if (!ok) {
    std::printf("%s %zu: bilgi yok\n", label, id);
    return;
  }
  std::printf("%s %zu: %s L=%.4f A=%.4f R=%.4f R2=%.4f aci=%.2f merkez=(%.3f %.3f %.3f) "
              "yon=(%.3f %.3f %.3f)\n",
              label, id, KindName(i.kind), i.length, i.area, i.radius, i.radius2, i.angleDeg,
              i.center[0], i.center[1], i.center[2], i.dir[0], i.dir[1], i.dir[2]);
}

int main(int argc, char** argv) {
  if (argc < 2) {
    std::fprintf(stderr, "Kullanim: %s dosya [cikti.obj] [--tree] [--info] [--faces] "
                         "[--measure e|f i e|f j] [--mass]\n", argv[0]);
    return 2;
  }
  MeshData mesh;
  std::string error;
  const auto t0 = std::chrono::steady_clock::now();
  if (!LoadModelFile(argv[1], MeshOptions(), mesh, error)) {
    std::fprintf(stderr, "HATA: %s\n", error.c_str());
    return 1;
  }
  for (int a = 2; a < argc; ++a) {
    if (std::strcmp(argv[a], "--as-mesh") != 0) continue;
    RawMesh raw;
    raw.tree = mesh.tree;
    raw.colors.push_back({0.7f, 0.72f, 0.75f});
    for (int p = 0; p < mesh.partCount(); ++p) {
      RawPart part;
      const int t0 = mesh.faceOffsets[mesh.partFaceOffsets[p]];
      const int t1 = mesh.faceOffsets[mesh.partFaceOffsets[p + 1]];
      for (int t = t0; t < t1; ++t) {
        for (int k = 0; k < 3; ++k) {
          const float* v = &mesh.vertices[mesh.indices[3 * t + k] * kFloatsPerVertex];
          part.positions.insert(part.positions.end(), {v[0], v[1], v[2]});
          part.indices.push_back(static_cast<uint32_t>(part.indices.size()));
        }
      }
      raw.parts.push_back(std::move(part));
    }
    const int cadFaces = mesh.faceCount();
    const size_t cadEdges = mesh.edgeShapes.size();
    mesh = MeshData();
    if (!BuildMeshModel(raw, mesh, error)) {
      std::fprintf(stderr, "HATA (mesh): %s\n", error.c_str());
      return 1;
    }
    mesh.format = FileFormat::Stl;
    std::printf("mesh olarak: CAD yuzey=%d kenar=%zu\n", cadFaces, cadEdges);
  }
  gMesh = &mesh;
  const double ms =
      std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
  std::printf("bicim=%d parca=%d yuzey=%d kenar=%zu kose=%zu ucgen=%zu sure=%.0fms\n",
              static_cast<int>(mesh.format), mesh.partCount(), mesh.faceCount(),
              mesh.edgeShapes.size(), mesh.vertexCount(), mesh.triangleCount(), ms);
  std::printf("bbox=(%.3f %.3f %.3f)-(%.3f %.3f %.3f)\n", mesh.bboxMin[0], mesh.bboxMin[1],
              mesh.bboxMin[2], mesh.bboxMax[0], mesh.bboxMax[1], mesh.bboxMax[2]);

  // Tutarlılık kontrolleri
  bool ok = mesh.faceOffsets.size() == mesh.faceShapes.size() + 1 &&
            mesh.edgeOffsets.size() == mesh.edgeShapes.size() + 1 &&
            mesh.partFaceOffsets.size() == mesh.partShapes.size() + 1 &&
            mesh.partEdgeOffsets.size() == mesh.partShapes.size() + 1 &&
            mesh.faceOffsets.back() == static_cast<int32_t>(mesh.triangleCount()) &&
            mesh.edgeOffsets.back() == static_cast<int32_t>(mesh.edges.size() / 6);
  std::printf("tutarlilik=%s\n", ok ? "OK" : "HATALI");

  for (int a = 2; a < argc; ++a) {
    if (std::strcmp(argv[a], "--tree") == 0) {
      for (const auto& n : mesh.tree) {
        std::printf("%*s%s [%s] parcalar=%d..%d\n", n.depth * 2, "",
                    n.name.empty() ? "(isimsiz)" : n.name.c_str(),
                    n.isAssembly ? "montaj" : "parca", n.leafStart, n.leafEnd);
      }
    } else if (std::strcmp(argv[a], "--info") == 0) {
      for (size_t e = 0; e < mesh.edgeShapes.size(); ++e) PrintInfo("kenar", e, mesh.edgeShapes[e]);
    } else if (std::strcmp(argv[a], "--faces") == 0) {
      for (size_t f = 0; f < mesh.faceShapes.size(); ++f) PrintInfo("yuzey", f, mesh.faceShapes[f]);
    } else if (std::strcmp(argv[a], "--measure") == 0 && a + 4 < argc) {
      const char* ka = argv[a + 1];
      const size_t ia = std::strtoul(argv[a + 2], nullptr, 10);
      const char* kb = argv[a + 3];
      const size_t ib = std::strtoul(argv[a + 4], nullptr, 10);
      a += 4;
      const TopoDS_Shape* sa = Pick(mesh, ka, ia);
      const TopoDS_Shape* sb = Pick(mesh, kb, ib);
      PairMeasure m;
      const bool measured =
          mesh.meshGeometry
              ? MeshMeasurePair(*mesh.meshGeometry, ka[0] == 'e' ? 0 : 1, static_cast<int>(ia),
                                kb[0] == 'e' ? 0 : 1, static_cast<int>(ib), m)
              : (sa && sb && MeasurePair(*sa, *sb, m));
      if (measured) {
        std::printf("olcum %s%zu-%s%zu: mesafe=%.4f p1=(%.3f %.3f %.3f) p2=(%.3f %.3f %.3f) "
                    "aci=%.2f merkez=%.4f paralel=%.4f\n",
                    ka, ia, kb, ib, m.distance, m.p1[0], m.p1[1], m.p1[2], m.p2[0], m.p2[1],
                    m.p2[2], m.angleDeg, m.centerDistance, m.parallelDistance);
      } else {
        std::printf("olcum %s%zu-%s%zu: basarisiz\n", ka, ia, kb, ib);
      }
    } else if (std::strcmp(argv[a], "--dxf") == 0 && a + 2 < argc) {
      const size_t fi = std::strtoul(argv[a + 1], nullptr, 10);
      DxfResult r;
      if (fi < mesh.faceShapes.size() && ExportFaceDxf(mesh.faceShapes[fi], r)) {
        FILE* df = std::fopen(argv[a + 2], "w");
        if (df) {
          std::fwrite(r.text.data(), 1, r.text.size(), df);
          std::fclose(df);
        }
        std::printf("dxf yuzey %zu: %.3f x %.3f mm, ic kontur=%d\n", fi, r.width, r.height, r.innerLoops);
      } else {
        std::printf("dxf yuzey %zu: duz degil\n", fi);
      }
      a += 2;
    } else if (std::strcmp(argv[a], "--drawing") == 0 && a + 1 < argc) {
      std::vector<DrawingView> views;
      const auto t0 = std::chrono::steady_clock::now();
      const bool ok2 = MakeDrawingViews(mesh.partShapes, true, true, views);
      const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
      std::printf("teknik resim: %s (%.0f ms)\n", ok2 ? "OK" : "HATA", ms);
      // Basit SVG: görünüşler yan yana
      FILE* sf = std::fopen(argv[a + 1], "w");
      if (sf) {
        std::fprintf(sf, "<svg xmlns='http://www.w3.org/2000/svg' viewBox='-10 -10 900 400' width='1800' height='800'>\n<rect x='-10' y='-10' width='900' height='400' fill='white'/>\n");
        for (size_t v = 0; v < views.size(); ++v) {
          float mn[2] = {1e30f, 1e30f}, mx[2] = {-1e30f, -1e30f};
          for (const auto* lst : {&views[v].visible, &views[v].hidden}) {
            for (size_t i = 0; i + 1 < lst->size(); i += 2) {
              mn[0] = std::min(mn[0], (*lst)[i]); mx[0] = std::max(mx[0], (*lst)[i]);
              mn[1] = std::min(mn[1], (*lst)[i + 1]); mx[1] = std::max(mx[1], (*lst)[i + 1]);
            }
          }
          std::printf("  gorunus %zu: gorunen=%zu gizli=%zu  bbox=(%.1f %.1f)-(%.1f %.1f)\n", v,
                      views[v].visible.size() / 4, views[v].hidden.size() / 4, mn[0], mn[1], mx[0], mx[1]);
          const float ox = 10.0f + v * 220.0f - mn[0], oy = 200.0f + mx[1];
          for (int k = 0; k < 2; ++k) {
            const auto& lst = k == 0 ? views[v].visible : views[v].hidden;
            for (size_t i = 0; i + 3 < lst.size(); i += 4) {
              std::fprintf(sf, "<line x1='%.2f' y1='%.2f' x2='%.2f' y2='%.2f' stroke='%s' stroke-width='0.4' %s/>\n",
                           ox + lst[i], oy - lst[i + 1], ox + lst[i + 2], oy - lst[i + 3], k == 0 ? "black" : "gray",
                           k == 0 ? "" : "stroke-dasharray='2,1'");
            }
          }
        }
        std::fprintf(sf, "</svg>\n");
        std::fclose(sf);
      }
      a += 1;
    } else if (std::strcmp(argv[a], "--holes") == 0) {
      std::vector<HoleInfo> holes;
      const auto t0 = std::chrono::steady_clock::now();
      FindHoles(mesh.faceShapes, mesh.partFaceOffsets, mesh.partShapes, holes);
      const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
      std::printf("delik sayisi=%zu (%.1f ms)\n", holes.size(), ms);
      for (const auto& h : holes) {
        std::printf("  parca=%d cap=%.3f derinlik=%.3f boydan=%d uc=%d kacak=%.2fx%.2f havsa=%.2f/%.0f "
                    "giris=(%.2f %.2f %.2f) yon=(%.2f %.2f %.2f) yuzey=%zu\n",
                    h.part, h.diameter, h.depth, h.through ? 1 : 0, h.drillPoint ? 1 : 0, h.cboreDiameter,
                    h.cboreDepth, h.csinkDiameter, h.csinkAngle, h.entry[0], h.entry[1], h.entry[2],
                    h.dir[0], h.dir[1], h.dir[2], h.faces.size());
      }
    } else if (std::strcmp(argv[a], "--mass") == 0) {
      MassProps p;
      if (mesh.meshGeometry ? MeshMassProps(*mesh.meshGeometry, nullptr, p)
                            : ComputeMassProps(mesh.partShapes, p)) {
        std::printf("hacim=%.3f alan=%.3f merkez=(%.3f %.3f %.3f) kati=%d\n", p.volume, p.area,
                    p.center[0], p.center[1], p.center[2], p.hasSolids ? 1 : 0);
      }
    }
  }

  if (argc >= 3 && argv[2][0] != '-') {
    FILE* f = std::fopen(argv[2], "w");
    if (!f) return 1;
    const int k = kFloatsPerVertex;
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
  return ok ? 0 : 3;
}
