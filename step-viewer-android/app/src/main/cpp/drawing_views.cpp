#include "drawing_views.h"

#include <BRepAdaptor_Curve.hxx>
#include <BRepLib.hxx>
#include <BRep_Builder.hxx>
#include <BRep_Tool.hxx>
#include <GCPnts_QuasiUniformDeflection.hxx>
#include <HLRAlgo_Projector.hxx>
#include <HLRBRep_PolyAlgo.hxx>
#include <HLRBRep_PolyHLRToShape.hxx>
#include <Standard_Failure.hxx>
#include <TopExp_Explorer.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Compound.hxx>
#include <gp_Ax2.hxx>

namespace stepviewer {
namespace {

void Collect(const TopoDS_Shape& s, std::vector<float>& out) {
  if (s.IsNull()) return;
  try {
    BRepLib::BuildCurves3d(s);
  } catch (const Standard_Failure&) {
  }
  for (TopExp_Explorer ex(s, TopAbs_EDGE); ex.More(); ex.Next()) {
    const TopoDS_Edge& e = TopoDS::Edge(ex.Current());
    if (BRep_Tool::Degenerated(e)) continue;
    try {
      BRepAdaptor_Curve c(e);
      const double f = c.FirstParameter(), l = c.LastParameter();
      if (c.GetType() == GeomAbs_Line) {
        const gp_Pnt a = c.Value(f), b = c.Value(l);
        out.insert(out.end(), {static_cast<float>(a.X()), static_cast<float>(a.Y()),
                               static_cast<float>(b.X()), static_cast<float>(b.Y())});
        continue;
      }
      GCPnts_QuasiUniformDeflection disc(c, 0.02, f, l);
      if (!disc.IsDone() || disc.NbPoints() < 2) continue;
      gp_Pnt prev = disc.Value(1);
      for (int i = 2; i <= disc.NbPoints(); ++i) {
        const gp_Pnt p = disc.Value(i);
        out.insert(out.end(), {static_cast<float>(prev.X()), static_cast<float>(prev.Y()),
                               static_cast<float>(p.X()), static_cast<float>(p.Y())});
        prev = p;
      }
    } catch (const Standard_Failure&) {
    }
  }
}

}  // namespace

bool MakeDrawingViews(const std::vector<TopoDS_Shape>& shapes, bool zUp, bool withHidden,
                      std::vector<DrawingView>& out) {
  out.assign(kDrawingViewCount, DrawingView());
  if (shapes.empty()) return false;
  TopoDS_Compound comp;
  BRep_Builder bb;
  bb.MakeCompound(comp);
  for (const auto& s : shapes) {
    if (!s.IsNull()) bb.Add(comp, s);
  }

  // Görüş yönü (göze doğru) ve ekran X'i
  gp_Dir dirs[kDrawingViewCount], xdirs[kDrawingViewCount];
  if (zUp) {
    dirs[0] = gp_Dir(0, -1, 0); xdirs[0] = gp_Dir(1, 0, 0);    // ön
    dirs[1] = gp_Dir(0, 0, 1);  xdirs[1] = gp_Dir(1, 0, 0);    // üst
    dirs[2] = gp_Dir(-1, 0, 0); xdirs[2] = gp_Dir(0, -1, 0);   // sol
    dirs[3] = gp_Dir(1, -1, 1); xdirs[3] = gp_Dir(1, 1, 0);    // izometrik
  } else {
    dirs[0] = gp_Dir(0, 0, 1);  xdirs[0] = gp_Dir(1, 0, 0);
    dirs[1] = gp_Dir(0, 1, 0);  xdirs[1] = gp_Dir(1, 0, 0);
    dirs[2] = gp_Dir(-1, 0, 0); xdirs[2] = gp_Dir(0, 0, 1);
    dirs[3] = gp_Dir(1, 1, 1);  xdirs[3] = gp_Dir(1, 0, -1);
  }
  bool any = false;
  for (int v = 0; v < kDrawingViewCount; ++v) {
    try {
      Handle(HLRBRep_PolyAlgo) algo = new HLRBRep_PolyAlgo();
      algo->Load(comp);
      algo->Projector(HLRAlgo_Projector(gp_Ax2(gp::Origin(), dirs[v], xdirs[v])));
      algo->Update();
      HLRBRep_PolyHLRToShape hlr;
      hlr.Update(algo);
      DrawingView& dv = out[static_cast<size_t>(v)];
      Collect(hlr.VCompound(), dv.visible);
      Collect(hlr.OutLineVCompound(), dv.visible);
      // İzometrikte gizli çizgi çizilmez
      if (withHidden && v != 3) {
        Collect(hlr.HCompound(), dv.hidden);
        Collect(hlr.OutLineHCompound(), dv.hidden);
      }
      any = any || !dv.visible.empty();
    } catch (const Standard_Failure&) {
    }
  }
  return any;
}

}  // namespace stepviewer
