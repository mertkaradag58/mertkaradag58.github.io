#include "step_mesher.h"

#include <BRepBndLib.hxx>
#include <BRepMesh_IncrementalMesh.hxx>
#include <BRep_Builder.hxx>
#include <BRep_Tool.hxx>
#include <Bnd_Box.hxx>
#include <IFSelect_ReturnStatus.hxx>
#include <Poly_Polygon3D.hxx>
#include <Poly_PolygonOnTriangulation.hxx>
#include <Poly_Triangulation.hxx>
#include <Quantity_Color.hxx>
#include <RWMesh_FaceIterator.hxx>
#include <STEPCAFControl_Reader.hxx>
#include <Standard_Failure.hxx>
#include <TDF_LabelSequence.hxx>
#include <TDocStd_Document.hxx>
#include <TopExp.hxx>
#include <TopTools_IndexedDataMapOfShapeListOfShape.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Compound.hxx>
#include <TopoDS_Edge.hxx>
#include <TopoDS_Face.hxx>
#include <XCAFApp_Application.hxx>
#include <XCAFDoc_DocumentTool.hxx>
#include <XCAFDoc_ShapeTool.hxx>
#include <XCAFPrs_DocumentExplorer.hxx>

#include <algorithm>
#include <cmath>
#include <exception>

namespace stepviewer {
namespace {

// Rengi olmayan yüzeyler için varsayılan metalik gri (sRGB).
constexpr float kDefaultColor[3] = {0.70f, 0.72f, 0.75f};

void AppendPoint(std::vector<float>& dst, const gp_Pnt& p) {
  dst.push_back(static_cast<float>(p.X()));
  dst.push_back(static_cast<float>(p.Y()));
  dst.push_back(static_cast<float>(p.Z()));
}

// Bir parçanın (yerleştirilmiş şekil) yüzey sınır kenarlarını çizgi segmentleri
// olarak ekler. BRepMesh sonrası kenarların üçgen ağı üzerindeki poligonları kullanılır.
void ExtractEdges(const TopoDS_Shape& shape, std::vector<float>& edges) {
  TopTools_IndexedDataMapOfShapeListOfShape edgeFaces;
  TopExp::MapShapesAndAncestors(shape, TopAbs_EDGE, TopAbs_FACE, edgeFaces);

  for (int i = 1; i <= edgeFaces.Extent(); ++i) {
    const TopoDS_Edge& edge = TopoDS::Edge(edgeFaces.FindKey(i));
    if (BRep_Tool::Degenerated(edge)) {
      continue;
    }

    const TopTools_ListOfShape& faces = edgeFaces.FindFromIndex(i);
    if (faces.IsEmpty()) {
      // Serbest kenar: varsa 3B poligonunu kullan.
      TopLoc_Location loc;
      Handle(Poly_Polygon3D) poly = BRep_Tool::Polygon3D(edge, loc);
      if (poly.IsNull() || poly->NbNodes() < 2) {
        continue;
      }
      const gp_Trsf trsf = loc.Transformation();
      const TColgp_Array1OfPnt& nodes = poly->Nodes();
      for (int k = nodes.Lower(); k < nodes.Upper(); ++k) {
        AppendPoint(edges, nodes(k).Transformed(trsf));
        AppendPoint(edges, nodes(k + 1).Transformed(trsf));
      }
      continue;
    }

    const TopoDS_Face& face = TopoDS::Face(faces.First());
    TopLoc_Location faceLoc;
    Handle(Poly_Triangulation) tri = BRep_Tool::Triangulation(face, faceLoc);
    if (tri.IsNull()) {
      continue;
    }
    Handle(Poly_PolygonOnTriangulation) poly =
        BRep_Tool::PolygonOnTriangulation(edge, tri, faceLoc);
    if (poly.IsNull() || poly->NbNodes() < 2) {
      continue;
    }
    const gp_Trsf trsf = faceLoc.Transformation();
    const TColStd_Array1OfInteger& nodeIdx = poly->Nodes();
    for (int k = nodeIdx.Lower(); k < nodeIdx.Upper(); ++k) {
      AppendPoint(edges, tri->Node(nodeIdx(k)).Transformed(trsf));
      AppendPoint(edges, tri->Node(nodeIdx(k + 1)).Transformed(trsf));
    }
  }
}

bool LoadImpl(const std::string& path, const MeshOptions& options, MeshData& out,
              std::string& error) {
  Handle(XCAFApp_Application) app = XCAFApp_Application::GetApplication();
  Handle(TDocStd_Document) doc;
  app->NewDocument("MDTV-XCAF", doc);

  // --- 1) STEP dosyasını oku ---
  STEPCAFControl_Reader reader;
  reader.SetColorMode(true);
  reader.SetNameMode(true);
  reader.SetLayerMode(false);
  reader.SetPropsMode(false);
  reader.SetGDTMode(false);
  reader.SetMatMode(false);
  reader.SetViewMode(false);

  if (reader.ReadFile(path.c_str()) != IFSelect_RetDone) {
    error = "STEP dosyası okunamadı (geçersiz veya bozuk dosya).";
    app->Close(doc);
    return false;
  }
  if (!reader.Transfer(doc)) {
    error = "STEP verisi aktarılamadı.";
    app->Close(doc);
    return false;
  }

  Handle(XCAFDoc_ShapeTool) shapeTool = XCAFDoc_DocumentTool::ShapeTool(doc->Main());
  TDF_LabelSequence freeShapes;
  shapeTool->GetFreeShapes(freeShapes);
  if (freeShapes.IsEmpty()) {
    error = "Dosyada geometri bulunamadı.";
    app->Close(doc);
    return false;
  }

  // --- 2) Tüm serbest şekilleri tek bir bileşikte topla ve üçgenle ---
  TopoDS_Compound compound;
  BRep_Builder builder;
  builder.MakeCompound(compound);
  for (TDF_LabelSequence::Iterator it(freeShapes); it.More(); it.Next()) {
    TopoDS_Shape shape = XCAFDoc_ShapeTool::GetShape(it.Value());
    if (!shape.IsNull()) {
      builder.Add(compound, shape);
    }
  }

  Bnd_Box box;
  BRepBndLib::Add(compound, box, false);
  if (box.IsVoid()) {
    error = "Dosyada görüntülenebilir geometri yok.";
    app->Close(doc);
    return false;
  }
  double xmin, ymin, zmin, xmax, ymax, zmax;
  box.Get(xmin, ymin, zmin, xmax, ymax, zmax);
  const double diag = std::sqrt((xmax - xmin) * (xmax - xmin) + (ymax - ymin) * (ymax - ymin) +
                                (zmax - zmin) * (zmax - zmin));
  const double linDefl = std::max(diag * options.relativeDeflection, 1.0e-6);

  // Paylaşılan geometri (örneğin montajda aynı cıvata) yalnızca bir kez üçgenlenir.
  BRepMesh_IncrementalMesh mesher(compound, linDefl, false, options.angularDeflection,
                                  /*isInParallel=*/true);

  // --- 3) Montaj ağacını gez, yerleşim ve renklerle birlikte üçgenleri topla ---
  float bmin[3] = {1e30f, 1e30f, 1e30f};
  float bmax[3] = {-1e30f, -1e30f, -1e30f};

  for (XCAFPrs_DocumentExplorer expl(doc, XCAFPrs_DocumentExplorerFlags_OnlyLeafNodes);
       expl.More(); expl.Next()) {
    const XCAFPrs_DocumentNode& node = expl.Current();
    ++out.partCount;

    for (RWMesh_FaceIterator fit(node.RefLabel, node.Location, true, node.Style); fit.More();
         fit.Next()) {
      if (fit.IsEmptyMesh()) {
        continue;
      }
      ++out.faceCount;

      float rgb[3] = {kDefaultColor[0], kDefaultColor[1], kDefaultColor[2]};
      if (fit.HasFaceColor()) {
        double r, g, b;
        fit.FaceColor().GetRGB().Values(r, g, b, Quantity_TOC_sRGB);
        rgb[0] = static_cast<float>(r);
        rgb[1] = static_cast<float>(g);
        rgb[2] = static_cast<float>(b);
      }

      const uint32_t base = static_cast<uint32_t>(out.vertexCount());
      out.vertices.reserve(out.vertices.size() + fit.NbNodes() * kFloatsPerVertex);
      for (int n = fit.NodeLower(); n <= fit.NodeUpper(); ++n) {
        const gp_Pnt p = fit.NodeTransformed(n);
        const gp_Dir d = fit.NormalTransformed(n);
        const float px = static_cast<float>(p.X());
        const float py = static_cast<float>(p.Y());
        const float pz = static_cast<float>(p.Z());
        out.vertices.insert(out.vertices.end(),
                            {px, py, pz, static_cast<float>(d.X()), static_cast<float>(d.Y()),
                             static_cast<float>(d.Z()), rgb[0], rgb[1], rgb[2]});
        bmin[0] = std::min(bmin[0], px);
        bmin[1] = std::min(bmin[1], py);
        bmin[2] = std::min(bmin[2], pz);
        bmax[0] = std::max(bmax[0], px);
        bmax[1] = std::max(bmax[1], py);
        bmax[2] = std::max(bmax[2], pz);
      }

      out.indices.reserve(out.indices.size() + fit.NbTriangles() * 3);
      for (int t = fit.ElemLower(); t <= fit.ElemUpper(); ++t) {
        int n1, n2, n3;
        fit.TriangleOriented(t).Get(n1, n2, n3);
        out.indices.push_back(base + static_cast<uint32_t>(n1 - fit.NodeLower()));
        out.indices.push_back(base + static_cast<uint32_t>(n2 - fit.NodeLower()));
        out.indices.push_back(base + static_cast<uint32_t>(n3 - fit.NodeLower()));
      }
    }

    if (options.extractEdges) {
      TopoDS_Shape partShape = XCAFDoc_ShapeTool::GetShape(node.RefLabel);
      if (!partShape.IsNull()) {
        ExtractEdges(partShape.Moved(node.Location), out.edges);
      }
    }
  }

  app->Close(doc);

  if (out.indices.empty()) {
    error = "Model üçgenlenemedi (yüzey bulunamadı).";
    return false;
  }
  for (int i = 0; i < 3; ++i) {
    out.bboxMin[i] = bmin[i];
    out.bboxMax[i] = bmax[i];
  }
  return true;
}

}  // namespace

bool LoadStepFile(const std::string& path, const MeshOptions& options, MeshData& out,
                  std::string& error) {
  out = MeshData();
  try {
    return LoadImpl(path, options, out, error);
  } catch (const Standard_Failure& e) {
    error = std::string("OCCT hatası: ") + e.GetMessageString();
  } catch (const std::bad_alloc&) {
    error = "Bellek yetersiz: model bu cihaz için çok büyük.";
  } catch (const std::exception& e) {
    error = std::string("Hata: ") + e.what();
  } catch (...) {
    error = "Bilinmeyen bir hata oluştu.";
  }
  out = MeshData();
  return false;
}

}  // namespace stepviewer
