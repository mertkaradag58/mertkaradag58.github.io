#include "step_mesher.h"

#include "mesh_model.h"

#include <BRepAdaptor_Curve.hxx>
#include <BRepAdaptor_Surface.hxx>
#include <BRepBuilderAPI_MakeVertex.hxx>
#include <BRepBndLib.hxx>
#include <BRepExtrema_DistShapeShape.hxx>
#include <BRepGProp.hxx>
#include <BRepMesh_IncrementalMesh.hxx>
#include <BRepTools.hxx>
#include <BRep_Builder.hxx>
#include <BRep_Tool.hxx>
#include <Bnd_Box.hxx>
#include <GCPnts_AbscissaPoint.hxx>
#include <GProp_GProps.hxx>
#include <IFSelect_ReturnStatus.hxx>
#include <IGESCAFControl_Reader.hxx>
#include <Poly_Polygon3D.hxx>
#include <Poly_PolygonOnTriangulation.hxx>
#include <Poly_Triangulation.hxx>
#include <Quantity_Color.hxx>
#include <RWMesh_FaceIterator.hxx>
#include <STEPCAFControl_Reader.hxx>
#include <Standard_Failure.hxx>
#include <TCollection_ExtendedString.hxx>
#include <TDF_LabelSequence.hxx>
#include <TDataStd_Name.hxx>
#include <TDocStd_Document.hxx>
#include <TopExp.hxx>
#include <TopExp_Explorer.hxx>
#include <TopTools_IndexedDataMapOfShapeListOfShape.hxx>
#include <TopTools_IndexedMapOfShape.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Compound.hxx>
#include <StepData_ConfParameters.hxx>
#include <XCAFApp_Application.hxx>
#include <XCAFDoc_DocumentTool.hxx>
#include <XCAFDoc_ShapeTool.hxx>
#include <XCAFPrs_DocumentExplorer.hxx>
#include <gp_Circ.hxx>
#include <gp_Cone.hxx>
#include <gp_Cylinder.hxx>
#include <gp_Elips.hxx>
#include <gp_Lin.hxx>
#include <gp_Pln.hxx>
#include <gp_Sphere.hxx>
#include <gp_Torus.hxx>

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstring>
#include <exception>
#include <fstream>

namespace stepviewer {
namespace {

// Rengi olmayan yüzeyler için varsayılan metalik gri (sRGB).
constexpr float kDefaultColor[3] = {0.70f, 0.72f, 0.75f};

void AppendPoint(std::vector<float>& dst, const gp_Pnt& p) {
  dst.push_back(static_cast<float>(p.X()));
  dst.push_back(static_cast<float>(p.Y()));
  dst.push_back(static_cast<float>(p.Z()));
}

// Tek bir kenarın çizgi segmentlerini ekler (BRepMesh sonrası poligonlardan).
void AppendEdgeSegments(const TopoDS_Edge& edge, const TopTools_ListOfShape& faces,
                        std::vector<float>& edges) {
  if (faces.IsEmpty()) {
    // Serbest kenar: varsa 3B poligonunu kullan.
    TopLoc_Location loc;
    Handle(Poly_Polygon3D) poly = BRep_Tool::Polygon3D(edge, loc);
    if (poly.IsNull() || poly->NbNodes() < 2) {
      return;
    }
    const gp_Trsf trsf = loc.Transformation();
    const TColgp_Array1OfPnt& nodes = poly->Nodes();
    for (int k = nodes.Lower(); k < nodes.Upper(); ++k) {
      AppendPoint(edges, nodes(k).Transformed(trsf));
      AppendPoint(edges, nodes(k + 1).Transformed(trsf));
    }
    return;
  }

  const TopoDS_Face& face = TopoDS::Face(faces.First());
  TopLoc_Location faceLoc;
  Handle(Poly_Triangulation) tri = BRep_Tool::Triangulation(face, faceLoc);
  if (tri.IsNull()) {
    return;
  }
  Handle(Poly_PolygonOnTriangulation) poly = BRep_Tool::PolygonOnTriangulation(edge, tri, faceLoc);
  if (poly.IsNull() || poly->NbNodes() < 2) {
    return;
  }
  const gp_Trsf trsf = faceLoc.Transformation();
  const TColStd_Array1OfInteger& nodeIdx = poly->Nodes();
  for (int k = nodeIdx.Lower(); k < nodeIdx.Upper(); ++k) {
    AppendPoint(edges, tri->Node(nodeIdx(k)).Transformed(trsf));
    AppendPoint(edges, tri->Node(nodeIdx(k + 1)).Transformed(trsf));
  }
}

// Bir parçanın (yerleştirilmiş şekil) kenarlarını ekler ve kenar kimliklerini kaydeder.
void ExtractEdges(const TopoDS_Shape& shape, MeshData& out) {
  TopTools_IndexedDataMapOfShapeListOfShape edgeFaces;
  TopExp::MapShapesAndAncestors(shape, TopAbs_EDGE, TopAbs_FACE, edgeFaces);

  for (int i = 1; i <= edgeFaces.Extent(); ++i) {
    const TopoDS_Edge& edge = TopoDS::Edge(edgeFaces.FindKey(i));
    if (BRep_Tool::Degenerated(edge)) {
      continue;
    }
    const size_t before = out.edges.size();
    AppendEdgeSegments(edge, edgeFaces.FindFromIndex(i), out.edges);
    if (out.edges.size() > before) {
      out.edgeShapes.push_back(edge);
      out.edgeOffsets.push_back(static_cast<int32_t>(out.edges.size() / 6));
    }
  }
}

std::string LabelName(const TDF_Label& label) {
  Handle(TDataStd_Name) attr;
  if (label.IsNull() || !label.FindAttribute(TDataStd_Name::GetID(), attr)) {
    return std::string();
  }
  const TCollection_ExtendedString& ext = attr->Get();
  std::string utf8(static_cast<size_t>(ext.LengthOfCString()) + 1, '\0');
  Standard_PCharacter buf = &utf8[0];
  const int len = ext.ToUTF8CString(buf);
  utf8.resize(static_cast<size_t>(std::max(len, 0)));
  // Boşlukları kırp
  const auto first = utf8.find_first_not_of(" \t\r\n");
  if (first == std::string::npos) return std::string();
  const auto last = utf8.find_last_not_of(" \t\r\n");
  return utf8.substr(first, last - first + 1);
}

std::string NodeName(const XCAFPrs_DocumentNode& node) {
  std::string name = LabelName(node.RefLabel);
  if (name.empty()) name = LabelName(node.Label);
  return name;
}

// Dosyadaki 8 bitlik baytlar geçerli UTF-8 mi? Eski SolidWorks gibi programlar
// Türkçe adları UTF-8 yerine Windows-1254 ile yazar ("Varsayılan" -> "Varsay\xFDlan").
// Geçersiz bir dizi bulunursa dosya CP1254 olarak okunur.
bool LooksLikeUtf8(const std::string& path) {
  std::ifstream in(path, std::ios::binary);
  if (!in) return true;
  std::vector<char> buf(1 << 20);
  int need = 0;  // beklenen devam baytı sayısı
  while (in) {
    in.read(buf.data(), static_cast<std::streamsize>(buf.size()));
    const std::streamsize n = in.gcount();
    for (std::streamsize i = 0; i < n; ++i) {
      const auto c = static_cast<unsigned char>(buf[static_cast<size_t>(i)]);
      if (need > 0) {
        if ((c & 0xC0) != 0x80) return false;
        --need;
      } else if (c < 0x80) {
        continue;
      } else if ((c & 0xE0) == 0xC0 && c >= 0xC2) {
        need = 1;
      } else if ((c & 0xF0) == 0xE0) {
        need = 2;
      } else if ((c & 0xF8) == 0xF0 && c <= 0xF4) {
        need = 3;
      } else {
        return false;
      }
    }
  }
  return need == 0;
}

// Belgeyi dosyadan doldurur.
bool ReadDocument(const std::string& path, FileFormat format, Handle(TDocStd_Document)& doc,
                  std::string& error) {
  switch (format) {
    case FileFormat::Step: {
      STEPCAFControl_Reader reader;
      reader.SetColorMode(true);
      reader.SetNameMode(true);
      reader.SetLayerMode(false);
      reader.SetPropsMode(false);
      reader.SetGDTMode(true);  // PMI: ölçü ve tolerans bilgileri
      reader.SetMatMode(false);
      reader.SetViewMode(false);
      StepData_ConfParameters params;
      params.InitFromStatic();
      params.ReadCodePage =
          LooksLikeUtf8(path) ? Resource_FormatType_UTF8 : Resource_FormatType_CP1254;
      if (reader.ReadFile(path.c_str(), params) != IFSelect_RetDone) {
        error = "read_failed:STEP";
        return false;
      }
      if (!reader.Transfer(doc)) {
        error = "transfer_failed:STEP";
        return false;
      }
      return true;
    }
    case FileFormat::Iges: {
      IGESCAFControl_Reader reader;
      reader.SetColorMode(true);
      reader.SetNameMode(true);
      reader.SetLayerMode(false);
      if (reader.ReadFile(path.c_str()) != IFSelect_RetDone) {
        error = "read_failed:IGES";
        return false;
      }
      if (!reader.Transfer(doc)) {
        error = "transfer_failed:IGES";
        return false;
      }
      return true;
    }
    case FileFormat::Brep: {
      TopoDS_Shape shape;
      BRep_Builder builder;
      if (!BRepTools::Read(shape, path.c_str(), builder) || shape.IsNull()) {
        error = "read_failed:BREP";
        return false;
      }
      Handle(XCAFDoc_ShapeTool) shapeTool = XCAFDoc_DocumentTool::ShapeTool(doc->Main());
      shapeTool->AddShape(shape, /*makeAssembly=*/true);
      return true;
    }
    default:
      error = "unsupported_format";
      return false;
  }
}

bool LoadImpl(const std::string& path, const MeshOptions& options, MeshData& out,
              std::string& error) {
  out.format = DetectFormat(path);
  if (out.format == FileFormat::Unknown) {
    error = "unsupported_format";
    return false;
  }

  // Mesh dosyaları (OBJ, STL, glTF, ...) ayrı yoldan okunur.
  if (IsMeshFormat(out.format)) {
    RawMesh raw;
    if (!ImportMeshFile(path, out.format, raw, error)) return false;
    return BuildMeshModel(raw, out, error);
  }

  Handle(XCAFApp_Application) app = XCAFApp_Application::GetApplication();
  Handle(TDocStd_Document) doc;
  app->NewDocument("MDTV-XCAF", doc);

  // --- 1) Dosyayı oku ---
  if (!ReadDocument(path, out.format, doc, error)) {
    app->Close(doc);
    return false;
  }

  Handle(XCAFDoc_ShapeTool) shapeTool = XCAFDoc_DocumentTool::ShapeTool(doc->Main());
  TDF_LabelSequence freeShapes;
  shapeTool->GetFreeShapes(freeShapes);
  if (freeShapes.IsEmpty()) {
    error = "no_geometry";
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
    error = "no_geometry";
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

  // PMI (yalnızca STEP; IGES/BREP'te yok)
  if (out.format == FileFormat::Step) {
    ExtractPmi(doc, linDefl, out.pmi);
  }

  // --- 3) Montaj ağacını gez: parçalar, yüzeyler, kenarlar, renkler ---
  float bmin[3] = {1e30f, 1e30f, 1e30f};
  float bmax[3] = {-1e30f, -1e30f, -1e30f};

  for (XCAFPrs_DocumentExplorer expl(doc, XCAFPrs_DocumentExplorerFlags_None); expl.More();
       expl.Next()) {
    const XCAFPrs_DocumentNode& node = expl.Current();
    TreeNode treeNode;
    treeNode.name = NodeName(node);
    treeNode.depth = std::max(expl.CurrentDepth(), 0);
    treeNode.isAssembly = node.IsAssembly;
    treeNode.leafStart = out.partCount();
    out.tree.push_back(treeNode);
    if (node.IsAssembly) {
      continue;
    }

    // Yaprak düğüm = parça
    for (RWMesh_FaceIterator fit(node.RefLabel, node.Location, true, node.Style); fit.More();
         fit.Next()) {
      if (fit.IsEmptyMesh()) {
        continue;
      }

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

      out.faceShapes.push_back(fit.Face());
      out.faceOffsets.push_back(static_cast<int32_t>(out.triangleCount()));
    }

    TopoDS_Shape partShape = XCAFDoc_ShapeTool::GetShape(node.RefLabel);
    if (!partShape.IsNull()) {
      partShape = partShape.Located(node.Location);
      if (options.extractEdges) {
        ExtractEdges(partShape, out);
      }
    }
    out.partShapes.push_back(partShape);
    out.partFaceOffsets.push_back(out.faceCount());
    out.partEdgeOffsets.push_back(static_cast<int32_t>(out.edgeShapes.size()));
  }

  app->Close(doc);

  // Ağaç düğümlerinin parça aralıklarını kapat: bir düğümün alt ağacı, kendisinden sonra
  // derinliği kendisininkinden büyük olan ardışık düğümlerdir.
  const int nodes = static_cast<int>(out.tree.size());
  for (int i = 0; i < nodes; ++i) {
    int end = out.partCount();
    for (int j = i + 1; j < nodes; ++j) {
      if (out.tree[j].depth <= out.tree[i].depth) {
        end = out.tree[j].leafStart;
        break;
      }
    }
    out.tree[i].leafEnd = end;
  }

  if (out.indices.empty()) {
    error = "no_geometry";
    return false;
  }
  for (int i = 0; i < 3; ++i) {
    out.bboxMin[i] = bmin[i];
    out.bboxMax[i] = bmax[i];
  }
  return true;
}

}  // namespace

namespace {

std::string LowerExtension(const std::string& path) {
  const size_t slash = path.find_last_of("/\\");
  const size_t dot = path.find_last_of('.');
  if (dot == std::string::npos || (slash != std::string::npos && dot < slash)) return std::string();
  std::string ext = path.substr(dot + 1);
  for (char& c : ext) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
  return ext;
}

}  // namespace

FileFormat DetectFormat(const std::string& path) {
  // Mesh biçimleri önce uzantıdan tanınır (STL/OBJ gibi biçimlerin belirgin bir imzası yok).
  const std::string ext = LowerExtension(path);
  if (ext == "obj") return FileFormat::Obj;
  if (ext == "stl") return FileFormat::Stl;
  if (ext == "gltf" || ext == "glb") return FileFormat::Gltf;
  if (ext == "dae") return FileFormat::Dae;
  if (ext == "3ds") return FileFormat::Max3ds;
  if (ext == "fbx") return FileFormat::Fbx;
  if (ext == "ply") return FileFormat::Ply;
  if (ext == "3mf") return FileFormat::ThreeMf;
  if (ext == "amf") return FileFormat::Amf;
  if (ext == "off") return FileFormat::Off;

  std::ifstream in(path, std::ios::binary);
  if (!in) return FileFormat::Unknown;
  std::string head(4096, '\0');
  in.read(&head[0], static_cast<std::streamsize>(head.size()));
  head.resize(static_cast<size_t>(in.gcount()));

  if (head.find("ISO-10303-21") != std::string::npos) return FileFormat::Step;
  if (head.find("DBRep_DrawableShape") != std::string::npos ||
      head.find("CASCADE Topology") != std::string::npos) {
    return FileFormat::Brep;
  }
  // IGES: 80 sütunluk satırlar, 73. sütunda bölüm harfi (ilk satır 'S' başlangıç bölümü).
  const size_t eol = head.find_first_of("\r\n");
  const std::string first = head.substr(0, eol);
  if (first.size() >= 73 && (first[72] == 'S' || first[72] == 'G')) return FileFormat::Iges;

  // Uzantısı bilinmeyen mesh dosyaları için imzalar
  if (head.compare(0, 4, "glTF") == 0) return FileFormat::Gltf;
  if (head.compare(0, 18, "Kaydara FBX Binary") == 0) return FileFormat::Fbx;
  if (head.compare(0, 3, "ply") == 0) return FileFormat::Ply;
  if (head.find("<COLLADA") != std::string::npos) return FileFormat::Dae;
  if (head.find("<amf") != std::string::npos) return FileFormat::Amf;
  if (head.compare(0, 3, "OFF") == 0) return FileFormat::Off;
  if (head.compare(0, 5, "solid") == 0 && head.find("facet") != std::string::npos) {
    return FileFormat::Stl;
  }
  if (head.size() >= 2 && static_cast<unsigned char>(head[0]) == 0x4D &&
      static_cast<unsigned char>(head[1]) == 0x4D && ext == "dat") {
    return FileFormat::Max3ds;  // 3DS ana parçası 0x4D4D
  }
  return FileFormat::Unknown;
}

bool LoadModelFile(const std::string& path, const MeshOptions& options, MeshData& out,
                   std::string& error) {
  out = MeshData();
  out.faceOffsets.push_back(0);
  out.edgeOffsets.push_back(0);
  out.partFaceOffsets.push_back(0);
  out.partEdgeOffsets.push_back(0);
  try {
    if (LoadImpl(path, options, out, error)) return true;
  } catch (const Standard_Failure& e) {
    error = std::string("OCCT: ") + e.GetMessageString();
  } catch (const std::bad_alloc&) {
    error = "out_of_memory";
  } catch (const std::exception& e) {
    error = std::string("Error: ") + e.what();
  } catch (...) {
    error = "unknown_error";
  }
  out = MeshData();
  return false;
}

// ---------------------------------------------------------------------------
// Ölçüm
// ---------------------------------------------------------------------------

namespace {

void ToArray(const gp_XYZ& v, double dst[3]) {
  dst[0] = v.X();
  dst[1] = v.Y();
  dst[2] = v.Z();
}

gp_Dir DirOf(const ShapeInfo& i) { return gp_Dir(i.dir[0], i.dir[1], i.dir[2]); }
gp_Pnt PntOf(const ShapeInfo& i) { return gp_Pnt(i.center[0], i.center[1], i.center[2]); }

bool IsLineLike(GeomKind k) {
  return k == GeomKind::Line || k == GeomKind::Cylinder || k == GeomKind::Cone;
}

// 0..90 derece arası katlanmış açı
double FoldedAngle(const gp_Dir& a, const gp_Dir& b) {
  double ang = a.Angle(b) * 180.0 / M_PI;
  if (ang > 90.0) ang = 180.0 - ang;
  return ang;
}

bool GetEdgeInfo(const TopoDS_Edge& edge, ShapeInfo& info) {
  if (BRep_Tool::Degenerated(edge)) return false;
  BRepAdaptor_Curve curve(edge);  // kenar konumunu uygular
  info.length = GCPnts_AbscissaPoint::Length(curve);
  switch (curve.GetType()) {
    case GeomAbs_Line:
      info.kind = GeomKind::Line;
      ToArray(curve.Line().Location().XYZ(), info.center);
      ToArray(curve.Line().Direction().XYZ(), info.dir);
      break;
    case GeomAbs_Circle: {
      const gp_Circ c = curve.Circle();
      info.kind = GeomKind::Circle;
      info.radius = c.Radius();
      ToArray(c.Location().XYZ(), info.center);
      ToArray(c.Axis().Direction().XYZ(), info.dir);
      break;
    }
    case GeomAbs_Ellipse: {
      const gp_Elips e = curve.Ellipse();
      info.kind = GeomKind::Ellipse;
      info.radius = e.MajorRadius();
      info.radius2 = e.MinorRadius();
      ToArray(e.Location().XYZ(), info.center);
      ToArray(e.Axis().Direction().XYZ(), info.dir);
      break;
    }
    default:
      info.kind = GeomKind::Curve;
      break;
  }
  return true;
}

// Yüzeyin sınır uzunluğu: dikiş (seam) ve dejenere kenarlar sayılmaz.
double FacePerimeter(const TopoDS_Face& face) {
  double total = 0;
  TopTools_IndexedMapOfShape edges;
  TopExp::MapShapes(face, TopAbs_EDGE, edges);
  for (int i = 1; i <= edges.Extent(); ++i) {
    const TopoDS_Edge& e = TopoDS::Edge(edges(i));
    if (BRep_Tool::Degenerated(e) || BRep_Tool::IsClosed(e, face)) continue;
    try {
      BRepAdaptor_Curve c(e);
      total += GCPnts_AbscissaPoint::Length(c);
    } catch (const Standard_Failure&) {
    }
  }
  return total;
}

bool GetFaceInfo(const TopoDS_Face& face, ShapeInfo& info) {
  GProp_GProps props;
  BRepGProp::SurfaceProperties(face, props);
  info.area = props.Mass();
  info.perimeter = FacePerimeter(face);

  BRepAdaptor_Surface surf(face);  // yüzey konumunu uygular
  switch (surf.GetType()) {
    case GeomAbs_Plane: {
      const gp_Pln pln = surf.Plane();
      info.kind = GeomKind::Plane;
      gp_Dir n = pln.Axis().Direction();
      if (face.Orientation() == TopAbs_REVERSED) n.Reverse();
      ToArray(pln.Location().XYZ(), info.center);
      ToArray(n.XYZ(), info.dir);
      break;
    }
    case GeomAbs_Cylinder: {
      const gp_Cylinder c = surf.Cylinder();
      info.kind = GeomKind::Cylinder;
      info.radius = c.Radius();
      ToArray(c.Location().XYZ(), info.center);
      ToArray(c.Axis().Direction().XYZ(), info.dir);
      break;
    }
    case GeomAbs_Cone: {
      const gp_Cone c = surf.Cone();
      info.kind = GeomKind::Cone;
      info.radius = c.RefRadius();
      info.angleDeg = c.SemiAngle() * 180.0 / M_PI;
      ToArray(c.Location().XYZ(), info.center);
      ToArray(c.Axis().Direction().XYZ(), info.dir);
      break;
    }
    case GeomAbs_Sphere: {
      const gp_Sphere s = surf.Sphere();
      info.kind = GeomKind::Sphere;
      info.radius = s.Radius();
      ToArray(s.Location().XYZ(), info.center);
      break;
    }
    case GeomAbs_Torus: {
      const gp_Torus t = surf.Torus();
      info.kind = GeomKind::Torus;
      info.radius = t.MajorRadius();
      info.radius2 = t.MinorRadius();
      ToArray(t.Location().XYZ(), info.center);
      ToArray(t.Axis().Direction().XYZ(), info.dir);
      break;
    }
    default:
      info.kind = GeomKind::Surface;
      break;
  }
  return true;
}

}  // namespace

bool GetShapeInfo(const TopoDS_Shape& shape, ShapeInfo& info) {
  info = ShapeInfo();
  try {
    if (shape.IsNull()) return false;
    if (shape.ShapeType() == TopAbs_EDGE) return GetEdgeInfo(TopoDS::Edge(shape), info);
    if (shape.ShapeType() == TopAbs_FACE) return GetFaceInfo(TopoDS::Face(shape), info);
    return false;
  } catch (const Standard_Failure&) {
    return false;
  }
}

bool MeasurePair(const TopoDS_Shape& a, const TopoDS_Shape& b, PairMeasure& out) {
  return MeasurePairWithInfo(a, nullptr, b, nullptr, out);
}

bool MeasurePointShape(const double point[3], const TopoDS_Shape& shape, PairMeasure& out) {
  out = PairMeasure();
  try {
    if (shape.IsNull()) return false;
    const TopoDS_Vertex v =
        BRepBuilderAPI_MakeVertex(gp_Pnt(point[0], point[1], point[2])).Vertex();
    BRepExtrema_DistShapeShape dist(v, shape);
    if (!dist.IsDone() || dist.NbSolution() < 1) return false;
    out.distance = dist.Value();
    out.p1[0] = point[0];
    out.p1[1] = point[1];
    out.p1[2] = point[2];
    ToArray(dist.PointOnShape2(1).XYZ(), out.p2);
    return true;
  } catch (const Standard_Failure&) {
    return false;
  }
}

bool MeasurePairWithInfo(const TopoDS_Shape& a, const ShapeInfo* pia, const TopoDS_Shape& b,
                         const ShapeInfo* pib, PairMeasure& out) {
  out = PairMeasure();
  try {
    BRepExtrema_DistShapeShape dist(a, b);
    if (!dist.IsDone() || dist.NbSolution() < 1) {
      return false;
    }
    out.distance = dist.Value();
    ToArray(dist.PointOnShape1(1).XYZ(), out.p1);
    ToArray(dist.PointOnShape2(1).XYZ(), out.p2);

    ShapeInfo ia, ib;
    if (pia != nullptr) {
      ia = *pia;
    } else if (!GetShapeInfo(a, ia)) {
      return true;
    }
    if (pib != nullptr) {
      ib = *pib;
    } else if (!GetShapeInfo(b, ib)) {
      return true;
    }

    const bool aLine = IsLineLike(ia.kind), bLine = IsLineLike(ib.kind);
    const bool aPlane = ia.kind == GeomKind::Plane, bPlane = ib.kind == GeomKind::Plane;

    // Açı: doğru-doğru (eksenler dahil), düzlem-düzlem, doğru-düzlem
    if ((aLine && bLine) || (aPlane && bPlane)) {
      out.angleDeg = FoldedAngle(DirOf(ia), DirOf(ib));
    } else if ((aLine && bPlane) || (aPlane && bLine)) {
      out.angleDeg = 90.0 - FoldedAngle(DirOf(ia), DirOf(ib));
    }

    // Paralel düzlemler arası dik mesafe
    if (aPlane && bPlane && out.angleDeg >= 0 && out.angleDeg < 0.01) {
      const gp_Vec d(PntOf(ia), PntOf(ib));
      out.parallelDistance = std::fabs(d.Dot(gp_Vec(DirOf(ia))));
    }

    // Çember merkezleri
    if (ia.kind == GeomKind::Circle && ib.kind == GeomKind::Circle) {
      out.centerDistance = PntOf(ia).Distance(PntOf(ib));
    }
    // Paralel eksenler (silindir/delik) arası mesafe
    const bool aAxis = ia.kind == GeomKind::Cylinder || ia.kind == GeomKind::Circle;
    const bool bAxis = ib.kind == GeomKind::Cylinder || ib.kind == GeomKind::Circle;
    if (aAxis && bAxis && out.centerDistance < 0 && FoldedAngle(DirOf(ia), DirOf(ib)) < 0.01) {
      const gp_Lin axisA(PntOf(ia), DirOf(ia));
      out.centerDistance = axisA.Distance(PntOf(ib));
    }
    return true;
  } catch (const Standard_Failure&) {
    return false;
  }
}

bool ComputeMassProps(const std::vector<TopoDS_Shape>& shapes, MassProps& out) {
  out = MassProps();
  try {
    GProp_GProps volume;
    GProp_GProps surface;
    for (const TopoDS_Shape& s : shapes) {
      if (s.IsNull()) continue;
      for (TopExp_Explorer ex(s, TopAbs_SOLID); ex.More(); ex.Next()) {
        GProp_GProps p;
        BRepGProp::VolumeProperties(ex.Current(), p);
        volume.Add(p);
        out.hasSolids = true;
      }
      GProp_GProps sp;
      BRepGProp::SurfaceProperties(s, sp);
      surface.Add(sp);
    }
    out.volume = std::fabs(volume.Mass());
    out.area = surface.Mass();
    const gp_Pnt c = (out.hasSolids && out.volume > 0) ? volume.CentreOfMass()
                                                       : surface.CentreOfMass();
    ToArray(c.XYZ(), out.center);
    return true;
  } catch (const Standard_Failure&) {
    return false;
  }
}

}  // namespace stepviewer
