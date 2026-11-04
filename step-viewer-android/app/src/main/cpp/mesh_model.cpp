#include "mesh_model.h"

#include <BRepBuilderAPI_MakeEdge.hxx>
#include <BRepBuilderAPI_MakeFace.hxx>
#include <BRepBuilderAPI_MakePolygon.hxx>
#include <BRepBuilderAPI_MakeVertex.hxx>
#include <BRep_Builder.hxx>
#include <GC_MakeArcOfCircle.hxx>
#include <Geom_TrimmedCurve.hxx>
#include <Standard_Failure.hxx>
#include <TopoDS_Compound.hxx>
#include <gp_Ax2.hxx>
#include <gp_Circ.hxx>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <numeric>
#include <unordered_map>

namespace stepviewer {
namespace {

// Rengi olmayan yüzeyler için varsayılan metalik gri (sRGB); step_mesher.cpp ile aynı.
constexpr float kDefaultColor[3] = {0.70f, 0.72f, 0.75f};
constexpr double kPi = 3.14159265358979323846;
// Komşu üçgenler arasında bundan küçük kırılım aynı eğri yüzeyde sayılır
// (24 kenarlı bir silindirde kırılım 15°, kutu köşesinde 90°).
constexpr double kSmoothAngleDeg = 40.0;
// Düz bölge: üçgen normalleri tohum normalinden en fazla bu kadar sapar.
constexpr double kPlanarAngleDeg = 1.0;
// Düz bölge ile eğri komşuları arasında en az bu kadar ortalama kırılım olmalı.
constexpr double kCreaseAngleDeg = 2.5;
// Kenar çizgisi bu açıdan keskin döndüğü noktada iki ayrı kenara bölünür.
constexpr double kEdgeTurnDeg = 35.0;
// Yüzey ölçümünde OCCT'ye verilecek en fazla üçgen (fazlası için köşe noktaları kullanılır).
constexpr size_t kMaxMeasureTriangles = 1500;

struct V3 {
  double x = 0, y = 0, z = 0;
  V3() = default;
  V3(double a, double b, double c) : x(a), y(b), z(c) {}
  V3 operator+(const V3& o) const { return {x + o.x, y + o.y, z + o.z}; }
  V3 operator-(const V3& o) const { return {x - o.x, y - o.y, z - o.z}; }
  V3 operator*(double s) const { return {x * s, y * s, z * s}; }
  V3& operator+=(const V3& o) {
    x += o.x;
    y += o.y;
    z += o.z;
    return *this;
  }
};
double Dot(const V3& a, const V3& b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
V3 Cross(const V3& a, const V3& b) {
  return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x};
}
double Len(const V3& a) { return std::sqrt(Dot(a, a)); }
V3 Unit(const V3& a) {
  const double l = Len(a);
  return l > 0 ? a * (1.0 / l) : V3(0, 0, 1);
}
gp_Pnt ToPnt(const V3& a) { return gp_Pnt(a.x, a.y, a.z); }
void ToArray(const V3& v, double dst[3]) {
  dst[0] = v.x;
  dst[1] = v.y;
  dst[2] = v.z;
}

V3 PosOf(const MeshGeometry& g, uint32_t i) {
  return {g.pos[3 * i], g.pos[3 * i + 1], g.pos[3 * i + 2]};
}

// Simetrik 3x3 matrisin özdeğerleri (küçükten büyüğe) ve özvektörleri (Jacobi).
void Eigen3(const double in[3][3], double evals[3], V3 evecs[3]) {
  double a[3][3], v[3][3] = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
  std::memcpy(a, in, sizeof(a));
  for (int sweep = 0; sweep < 50; ++sweep) {
    const double off = a[0][1] * a[0][1] + a[0][2] * a[0][2] + a[1][2] * a[1][2];
    const double diag = a[0][0] * a[0][0] + a[1][1] * a[1][1] + a[2][2] * a[2][2];
    if (off <= 1e-24 * diag || off == 0.0) break;
    for (int p = 0; p < 2; ++p) {
      for (int q = p + 1; q < 3; ++q) {
        if (a[p][q] == 0.0) continue;
        const double theta = (a[q][q] - a[p][p]) / (2.0 * a[p][q]);
        const double t = (theta >= 0 ? 1.0 : -1.0) / (std::fabs(theta) + std::sqrt(theta * theta + 1.0));
        const double c = 1.0 / std::sqrt(t * t + 1.0), s = t * c;
        for (int k = 0; k < 3; ++k) {
          const double akp = a[k][p], akq = a[k][q];
          a[k][p] = c * akp - s * akq;
          a[k][q] = s * akp + c * akq;
        }
        for (int k = 0; k < 3; ++k) {
          const double apk = a[p][k], aqk = a[q][k];
          a[p][k] = c * apk - s * aqk;
          a[q][k] = s * apk + c * aqk;
        }
        for (int k = 0; k < 3; ++k) {
          const double vkp = v[k][p], vkq = v[k][q];
          v[k][p] = c * vkp - s * vkq;
          v[k][q] = s * vkp + c * vkq;
        }
      }
    }
  }
  int idx[3] = {0, 1, 2};
  std::sort(idx, idx + 3, [&](int i, int j) { return a[i][i] < a[j][j]; });
  for (int i = 0; i < 3; ++i) {
    evals[i] = a[idx[i]][idx[i]];
    evecs[i] = Unit(V3(v[0][idx[i]], v[1][idx[i]], v[2][idx[i]]));
  }
}

// n x n (n <= 4) doğrusal sistem, kısmi pivotlu Gauss eleme. Tekilse false.
bool Solve(int n, double m[4][5], double x[4]) {
  for (int c = 0; c < n; ++c) {
    int piv = c;
    for (int r = c + 1; r < n; ++r) {
      if (std::fabs(m[r][c]) > std::fabs(m[piv][c])) piv = r;
    }
    if (std::fabs(m[piv][c]) < 1e-300) return false;
    if (piv != c) {
      for (int k = 0; k <= n; ++k) std::swap(m[c][k], m[piv][k]);
    }
    for (int r = c + 1; r < n; ++r) {
      const double f = m[r][c] / m[c][c];
      for (int k = c; k <= n; ++k) m[r][k] -= f * m[c][k];
    }
  }
  for (int r = n - 1; r >= 0; --r) {
    double s = m[r][n];
    for (int k = r + 1; k < n; ++k) s -= m[r][k] * x[k];
    x[r] = s / m[r][r];
    if (!std::isfinite(x[r])) return false;
  }
  return true;
}

struct CircleFit {
  double cx = 0, cy = 0, r = 0, maxDev = 0;
};

// 2B çember uydurma (Kasa yöntemi), noktalar ortalamaya göre kaydırılarak.
bool FitCircle2D(const std::vector<double>& xs, const std::vector<double>& ys, CircleFit& out) {
  const size_t n = xs.size();
  if (n < 3) return false;
  double mx = 0, my = 0;
  for (size_t i = 0; i < n; ++i) {
    mx += xs[i];
    my += ys[i];
  }
  mx /= n;
  my /= n;
  double m[4][5] = {};
  for (size_t i = 0; i < n; ++i) {
    const double x = xs[i] - mx, y = ys[i] - my, z = x * x + y * y;
    m[0][0] += x * x;
    m[0][1] += x * y;
    m[0][2] += x;
    m[1][1] += y * y;
    m[1][2] += y;
    m[0][3] -= x * z;
    m[1][3] -= y * z;
    m[2][3] -= z;
  }
  m[1][0] = m[0][1];
  m[2][0] = m[0][2];
  m[2][1] = m[1][2];
  m[2][2] = static_cast<double>(n);
  double sol[4];
  if (!Solve(3, m, sol)) return false;
  const double cx = -sol[0] / 2, cy = -sol[1] / 2;
  const double r2 = cx * cx + cy * cy - sol[2];
  if (!(r2 > 0)) return false;
  out.cx = cx + mx;
  out.cy = cy + my;
  out.r = std::sqrt(r2);
  out.maxDev = 0;
  for (size_t i = 0; i < n; ++i) {
    const double d = std::hypot(xs[i] - out.cx, ys[i] - out.cy);
    out.maxDev = std::max(out.maxDev, std::fabs(d - out.r));
  }
  return true;
}

// Noktalara düzlem: ağırlık merkezi ve normal (en küçük özdeğerin vektörü).
void FitPlane(const std::vector<V3>& pts, V3& centroid, V3& normal, V3& u, V3& v) {
  centroid = V3();
  for (const V3& p : pts) centroid += p;
  centroid = centroid * (1.0 / static_cast<double>(pts.size()));
  double c[3][3] = {};
  for (const V3& p : pts) {
    const V3 d = p - centroid;
    const double a[3] = {d.x, d.y, d.z};
    for (int i = 0; i < 3; ++i)
      for (int j = 0; j < 3; ++j) c[i][j] += a[i] * a[j];
  }
  double ev[3];
  V3 vec[3];
  Eigen3(c, ev, vec);
  normal = vec[0];
  u = vec[2];
  v = Unit(Cross(normal, u));
}

// ---------------------------------------------------------------------------
// Bölgelere ayırma
// ---------------------------------------------------------------------------

struct QKey {
  int64_t x, y, z;
  bool operator==(const QKey& o) const { return x == o.x && y == o.y && z == o.z; }
};
struct QKeyHash {
  size_t operator()(const QKey& k) const {
    uint64_t h = static_cast<uint64_t>(k.x) * 0x9E3779B97F4A7C15ull;
    h ^= static_cast<uint64_t>(k.y) * 0xC2B2AE3D27D4EB4Full + (h << 6) + (h >> 2);
    h ^= static_cast<uint64_t>(k.z) * 0x165667B19E3779F9ull + (h << 6) + (h >> 2);
    return static_cast<size_t>(h);
  }
};

struct BoundaryEdge {
  uint32_t a, b;
  int32_t r1, r2;  // iki yandaki bölgeler (r1 <= r2); açık kenarda r1 = -1, dallanmada -2
};

void ProcessPart(RawPart& part, const std::vector<std::array<float, 3>>& colors, double weldEps,
                 double tol, MeshGeometry& g, MeshData& out) {
  const size_t nIn = part.positions.size() / 3;

  // 1) Aynı konumdaki köşeleri kaynaştır (STL gibi dosyalarda her üçgen ayrı köşelere sahiptir).
  std::vector<uint32_t> remap(nIn);
  std::vector<double> P;
  {
    std::unordered_map<QKey, uint32_t, QKeyHash> map;
    map.reserve(nIn);
    const double inv = 1.0 / weldEps;
    for (size_t i = 0; i < nIn; ++i) {
      const double x = part.positions[3 * i], y = part.positions[3 * i + 1],
                   z = part.positions[3 * i + 2];
      const QKey key{std::llround(x * inv), std::llround(y * inv), std::llround(z * inv)};
      auto res = map.emplace(key, static_cast<uint32_t>(P.size() / 3));
      if (res.second) {
        P.push_back(x);
        P.push_back(y);
        P.push_back(z);
      }
      remap[i] = res.first->second;
    }
  }
  const size_t nv = P.size() / 3;
  auto Pt = [&P](uint32_t i) { return V3(P[3 * i], P[3 * i + 1], P[3 * i + 2]); };

  // 2) Üçgenler: dejenere olanları at, normal ve alanları hesapla.
  std::vector<uint32_t> T;
  std::vector<uint32_t> mat;
  std::vector<V3> N;
  std::vector<double> A;
  {
    const size_t ntIn = part.indices.size() / 3;
    T.reserve(ntIn * 3);
    const double minCross = weldEps * weldEps;
    for (size_t t = 0; t < ntIn; ++t) {
      const uint32_t i0 = part.indices[3 * t], i1 = part.indices[3 * t + 1],
                     i2 = part.indices[3 * t + 2];
      if (i0 >= nIn || i1 >= nIn || i2 >= nIn) continue;
      const uint32_t a = remap[i0], b = remap[i1], c = remap[i2];
      if (a == b || b == c || a == c) continue;
      const V3 cr = Cross(Pt(b) - Pt(a), Pt(c) - Pt(a));
      const double l = Len(cr);
      if (!(l > minCross)) continue;
      T.push_back(a);
      T.push_back(b);
      T.push_back(c);
      N.push_back(cr * (1.0 / l));
      A.push_back(l * 0.5);
      uint32_t m = t < part.triMaterial.size() ? part.triMaterial[t] : 0;
      if (m >= colors.size()) m = 0;
      mat.push_back(m);
    }
  }
  std::vector<uint32_t>().swap(remap);
  std::vector<float>().swap(part.positions);
  std::vector<uint32_t>().swap(part.indices);
  std::vector<uint32_t>().swap(part.triMaterial);

  const size_t nt = T.size() / 3;
  const auto finishPart = [&](bool closed) {
    out.partShapes.push_back(TopoDS_Shape());
    out.partFaceOffsets.push_back(out.faceCount());
    out.partEdgeOffsets.push_back(static_cast<int32_t>(out.edgeShapes.size()));
    g.partFaceOffsets.push_back(static_cast<int32_t>(g.faceOffsets.size() - 1));
    g.partClosed.push_back(closed ? 1 : 0);
  };
  if (nt == 0) {
    finishPart(false);
    return;
  }

  // 3) Komşuluk: her üçgen kenarı için karşı üçgen (yalnızca tam iki üçgen paylaşıyorsa).
  std::vector<std::pair<uint64_t, uint32_t>> E;
  E.reserve(nt * 3);
  for (size_t t = 0; t < nt; ++t) {
    for (int k = 0; k < 3; ++k) {
      const uint64_t a = T[3 * t + k], b = T[3 * t + (k + 1) % 3];
      const uint64_t key = a < b ? (a << 32) | b : (b << 32) | a;
      E.emplace_back(key, static_cast<uint32_t>(3 * t + k));
    }
  }
  std::sort(E.begin(), E.end());
  std::vector<int32_t> nb(nt * 3, -1);
  bool closed = true;
  for (size_t i = 0; i < E.size();) {
    size_t j = i + 1;
    while (j < E.size() && E[j].first == E[i].first) ++j;
    if (j - i == 2) {
      const uint32_t h0 = E[i].second, h1 = E[i + 1].second;
      nb[h0] = static_cast<int32_t>(h1 / 3);
      nb[h1] = static_cast<int32_t>(h0 / 3);
      // Kapalı ve tutarlı yönlü mü? Ortak kenar iki üçgende ters yönde geçmeli.
      if (T[h0] == T[h1]) closed = false;
    } else {
      closed = false;
    }
    i = j;
  }

  // 4a) Düz bölgeler: en büyük üçgenlerden başlayarak aynı düzlemdeki komşuları topla.
  std::vector<int32_t> region(nt, -1);
  int32_t nRegions = 0;
  const double cosPlanar = std::cos(kPlanarAngleDeg * kPi / 180.0);
  const double cosSmooth = std::cos(kSmoothAngleDeg * kPi / 180.0);
  const double planeTol = std::max(tol, weldEps * 10);
  const double cosCrease = std::cos(kCreaseAngleDeg * kPi / 180.0);
  {
    std::vector<uint32_t> order(nt);
    std::iota(order.begin(), order.end(), 0u);
    std::sort(order.begin(), order.end(), [&A](uint32_t a, uint32_t b) { return A[a] > A[b]; });
    std::vector<uint32_t> stamp(nt, UINT32_MAX);
    std::vector<uint8_t> tried(nt, 0);
    std::vector<uint32_t> stack, members;
    for (uint32_t seed : order) {
      if (region[seed] >= 0 || tried[seed]) continue;
      const V3 n0 = N[seed];
      const double d0 = Dot(n0, Pt(T[3 * seed]));
      members.clear();
      stack.assign(1, seed);
      stamp[seed] = seed;
      while (!stack.empty()) {
        const uint32_t t = stack.back();
        stack.pop_back();
        members.push_back(t);
        for (int k = 0; k < 3; ++k) {
          const int32_t ui = nb[3 * t + k];
          if (ui < 0) continue;
          const uint32_t u = static_cast<uint32_t>(ui);
          if (region[u] >= 0 || tried[u] || stamp[u] == seed || mat[u] != mat[seed]) continue;
          if (Dot(N[u], n0) < cosPlanar) continue;
          bool onPlane = true;
          for (int q = 0; q < 3 && onPlane; ++q) {
            onPlane = std::fabs(Dot(n0, Pt(T[3 * u + q])) - d0) <= planeTol;
          }
          if (!onPlane) continue;
          stamp[u] = seed;
          stack.push_back(u);
        }
      }
      // Kabul: sınırları keskinse ya da çevresindeki yumuşak geçişli üçgenlerden çok büyükse
      // (ör. kavisli köşeye komşu kutu yüzü). Silindir üzerindeki dar şeritler reddedilir.
      // Arazi gibi her yeri hafif eğimli yüzeylerde komşularla arada belirgin bir kırılım yoktur;
      // böyle bölgeler düz sayılmaz (yoksa binlerce küçük parçaya bölünür).
      double area = 0, nbArea = 0, nbCos = 0;
      int nbCount = 0;
      for (uint32_t t : members) {
        area += A[t];
        for (int k = 0; k < 3; ++k) {
          const int32_t ui = nb[3 * t + k];
          if (ui < 0) continue;
          const uint32_t u = static_cast<uint32_t>(ui);
          if (stamp[u] == seed && region[u] < 0 && !tried[u]) continue;  // bölgenin içinde
          const double c = Dot(N[u], n0);
          if (mat[u] == mat[seed] && c >= cosSmooth) {
            nbArea += A[u];
            nbCos += c;
            ++nbCount;
          }
        }
      }
      const bool accept =
          nbCount == 0 || (members.size() >= 2 && area > 4.0 * (nbArea / nbCount) &&
                           nbCos / nbCount <= cosCrease);
      for (uint32_t t : members) {
        if (accept) {
          region[t] = nRegions;
        } else {
          tried[t] = 1;
        }
      }
      if (accept) ++nRegions;
    }
  }

  // 4b) Kalan üçgenler: yumuşak geçişli komşularla eğri yüzey bölgeleri.
  {
    std::vector<uint32_t> stack;
    for (uint32_t s = 0; s < nt; ++s) {
      if (region[s] >= 0) continue;
      const int32_t r = nRegions++;
      region[s] = r;
      stack.assign(1, s);
      while (!stack.empty()) {
        const uint32_t t = stack.back();
        stack.pop_back();
        for (int k = 0; k < 3; ++k) {
          const int32_t ui = nb[3 * t + k];
          if (ui < 0) continue;
          const uint32_t u = static_cast<uint32_t>(ui);
          if (region[u] >= 0 || mat[u] != mat[t] || Dot(N[u], N[t]) < cosSmooth) continue;
          region[u] = r;
          stack.push_back(u);
        }
      }
    }
  }

  // 5) Üçgenleri bölgeye göre sırala ve köşeleri bölge başına çoğaltarak yaz
  // (bölge içinde yumuşak, bölgeler arasında keskin gölgelendirme).
  std::vector<uint32_t> regionStart(static_cast<size_t>(nRegions) + 1, 0);
  for (size_t t = 0; t < nt; ++t) ++regionStart[static_cast<size_t>(region[t]) + 1];
  for (int32_t r = 0; r < nRegions; ++r) regionStart[r + 1] += regionStart[r];
  std::vector<uint32_t> sorted(nt);
  {
    std::vector<uint32_t> fill(regionStart.begin(), regionStart.end() - 1);
    for (uint32_t t = 0; t < nt; ++t) sorted[fill[static_cast<size_t>(region[t])]++] = t;
  }

  const uint32_t gBase = static_cast<uint32_t>(g.pos.size() / 3);
  g.pos.reserve(g.pos.size() + P.size());
  for (double c : P) g.pos.push_back(static_cast<float>(c));

  const size_t vBase = out.vertexCount();
  std::vector<int32_t> vStamp(nv, -1);
  std::vector<uint32_t> vOut(nv, 0);
  for (int32_t r = 0; r < nRegions; ++r) {
    for (uint32_t s = regionStart[r]; s < regionStart[r + 1]; ++s) {
      const uint32_t t = sorted[s];
      const float* col = colors.empty() ? kDefaultColor : colors[mat[t]].data();
      for (int k = 0; k < 3; ++k) {
        const uint32_t v = T[3 * t + k];
        if (vStamp[v] != r) {
          vStamp[v] = r;
          vOut[v] = static_cast<uint32_t>(out.vertexCount());
          out.vertices.insert(out.vertices.end(),
                              {static_cast<float>(P[3 * v]), static_cast<float>(P[3 * v + 1]),
                               static_cast<float>(P[3 * v + 2]), 0.f, 0.f, 0.f, col[0], col[1],
                               col[2]});
        }
        float* nrm = &out.vertices[static_cast<size_t>(vOut[v]) * kFloatsPerVertex + 3];
        nrm[0] += static_cast<float>(N[t].x * A[t]);
        nrm[1] += static_cast<float>(N[t].y * A[t]);
        nrm[2] += static_cast<float>(N[t].z * A[t]);
        out.indices.push_back(vOut[v]);
        g.tris.push_back(gBase + v);
      }
    }
    out.faceShapes.push_back(TopoDS_Face());
    out.faceOffsets.push_back(static_cast<int32_t>(out.triangleCount()));
    g.faceOffsets.push_back(static_cast<int32_t>(g.tris.size() / 3));
  }
  for (size_t v = vBase; v < out.vertexCount(); ++v) {
    float* nrm = &out.vertices[v * kFloatsPerVertex + 3];
    const float l = std::sqrt(nrm[0] * nrm[0] + nrm[1] * nrm[1] + nrm[2] * nrm[2]);
    if (l > 0) {
      nrm[0] /= l;
      nrm[1] /= l;
      nrm[2] /= l;
    } else {
      nrm[2] = 1.f;
    }
  }
  std::vector<int32_t>().swap(vStamp);
  std::vector<uint32_t>().swap(vOut);

  // 6) Kenarlar: bölge sınırları ve açık kenarlar, çoklu çizgilere zincirlenir.
  std::vector<BoundaryEdge> be;
  for (size_t i = 0; i < E.size();) {
    size_t j = i + 1;
    while (j < E.size() && E[j].first == E[i].first) ++j;
    const uint32_t h = E[i].second, t0 = h / 3, k0 = h % 3;
    BoundaryEdge e{T[3 * t0 + k0], T[3 * t0 + (k0 + 1) % 3], -1, -1};
    bool keep = true;
    if (j - i == 2) {
      const int32_t ra = region[t0], rb = region[E[i + 1].second / 3];
      keep = ra != rb;
      e.r1 = std::min(ra, rb);
      e.r2 = std::max(ra, rb);
    } else if (j - i == 1) {
      e.r1 = -1;
      e.r2 = region[t0];
    } else {
      e.r1 = e.r2 = -2;
    }
    if (keep) be.push_back(e);
    i = j;
  }
  std::vector<std::pair<uint64_t, uint32_t>>().swap(E);

  std::vector<uint32_t> degStart(nv + 1, 0);
  for (const BoundaryEdge& e : be) {
    ++degStart[e.a + 1];
    ++degStart[e.b + 1];
  }
  for (size_t v = 0; v < nv; ++v) degStart[v + 1] += degStart[v];
  std::vector<uint32_t> adj(degStart[nv]);
  {
    std::vector<uint32_t> fill(degStart.begin(), degStart.end() - 1);
    for (uint32_t i = 0; i < be.size(); ++i) {
      adj[fill[be[i].a]++] = i;
      adj[fill[be[i].b]++] = i;
    }
  }
  const auto other = [&be](uint32_t e, uint32_t v) { return be[e].a == v ? be[e].b : be[e].a; };
  const double cosTurn = std::cos(kEdgeTurnDeg * kPi / 180.0);
  std::vector<uint8_t> isBreak(nv, 0);
  for (uint32_t v = 0; v < nv; ++v) {
    const uint32_t deg = degStart[v + 1] - degStart[v];
    if (deg == 0) continue;
    if (deg != 2) {
      isBreak[v] = 1;
      continue;
    }
    const uint32_t e1 = adj[degStart[v]], e2 = adj[degStart[v] + 1];
    if (be[e1].r1 != be[e2].r1 || be[e1].r2 != be[e2].r2) {
      isBreak[v] = 1;
      continue;
    }
    const V3 d1 = Unit(Pt(v) - Pt(other(e1, v))), d2 = Unit(Pt(other(e2, v)) - Pt(v));
    if (Dot(d1, d2) < cosTurn) isBreak[v] = 1;
  }

  std::vector<uint8_t> used(be.size(), 0);
  std::vector<uint32_t> chain;
  const auto emitChain = [&]() {
    if (chain.size() < 2) return;
    for (size_t i = 0; i + 1 < chain.size(); ++i) {
      for (uint32_t v : {chain[i], chain[i + 1]}) {
        out.edges.push_back(static_cast<float>(P[3 * v]));
        out.edges.push_back(static_cast<float>(P[3 * v + 1]));
        out.edges.push_back(static_cast<float>(P[3 * v + 2]));
      }
    }
    for (uint32_t v : chain) {
      g.edgePts.push_back(static_cast<float>(P[3 * v]));
      g.edgePts.push_back(static_cast<float>(P[3 * v + 1]));
      g.edgePts.push_back(static_cast<float>(P[3 * v + 2]));
    }
    out.edgeShapes.push_back(TopoDS_Edge());
    out.edgeOffsets.push_back(static_cast<int32_t>(out.edges.size() / 6));
    g.edgePtOffsets.push_back(static_cast<int32_t>(g.edgePts.size() / 3));
  };
  const auto walk = [&](uint32_t start, uint32_t firstEdge) {
    chain.assign(1, start);
    uint32_t cur = start, e = firstEdge;
    for (;;) {
      used[e] = 1;
      cur = other(e, cur);
      chain.push_back(cur);
      if (cur == start || isBreak[cur]) break;
      uint32_t next = UINT32_MAX;
      for (uint32_t i = degStart[cur]; i < degStart[cur + 1]; ++i) {
        if (!used[adj[i]]) {
          next = adj[i];
          break;
        }
      }
      if (next == UINT32_MAX) break;
      e = next;
    }
    emitChain();
  };
  for (uint32_t v = 0; v < nv; ++v) {
    if (!isBreak[v]) continue;
    for (uint32_t i = degStart[v]; i < degStart[v + 1]; ++i) {
      if (!used[adj[i]]) walk(v, adj[i]);
    }
  }
  for (uint32_t e = 0; e < be.size(); ++e) {
    if (!used[e]) walk(be[e].a, e);  // kırılma noktası olmayan kapalı döngüler
  }

  finishPart(closed);
}

// ---------------------------------------------------------------------------
// Ölçüm yardımcıları
// ---------------------------------------------------------------------------

V3 EdgePt(const MeshGeometry& g, int32_t i) {
  return {g.edgePts[3 * i], g.edgePts[3 * i + 1], g.edgePts[3 * i + 2]};
}

std::vector<V3> EdgePolyline(const MeshGeometry& g, int id) {
  std::vector<V3> pts;
  if (id < 0 || static_cast<size_t>(id) + 1 >= g.edgePtOffsets.size()) return pts;
  for (int32_t i = g.edgePtOffsets[id]; i < g.edgePtOffsets[id + 1]; ++i) pts.push_back(EdgePt(g, i));
  return pts;
}

struct EdgeFit {
  ShapeInfo info;
  std::vector<V3> pts;
  bool closed = false;
};

bool FitEdge(const MeshGeometry& g, int id, EdgeFit& f) {
  f = EdgeFit();
  f.pts = EdgePolyline(g, id);
  const size_t m = f.pts.size();
  if (m < 2) return false;
  ShapeInfo& info = f.info;
  double len = 0;
  for (size_t i = 0; i + 1 < m; ++i) len += Len(f.pts[i + 1] - f.pts[i]);
  info.length = len;
  info.kind = GeomKind::Curve;
  f.closed = m > 3 && Len(f.pts.front() - f.pts.back()) <= g.tol;
  const double tol = std::max(g.tol, 1e-4 * len);

  // Doğru mu?
  if (!f.closed) {
    const V3 p0 = f.pts.front();
    const V3 d = Unit(f.pts.back() - p0);
    double maxDev = 0;
    for (const V3& p : f.pts) maxDev = std::max(maxDev, Len(Cross(p - p0, d)));
    if (maxDev <= tol) {
      info.kind = GeomKind::Line;
      ToArray(p0, info.center);
      ToArray(d, info.dir);
      return true;
    }
  }

  // Çember / yay mı? (Üçgenlenmiş bir çemberin köşeleri tam olarak çemberin üzerindedir.)
  const size_t distinct = f.closed ? m - 1 : m;
  if (distinct >= (f.closed ? 6u : 5u)) {
    std::vector<V3> uniq(f.pts.begin(), f.pts.begin() + static_cast<long>(distinct));
    V3 c, n, u, v;
    FitPlane(uniq, c, n, u, v);
    double planeDev = 0;
    for (const V3& p : uniq) planeDev = std::max(planeDev, std::fabs(Dot(p - c, n)));
    std::vector<double> xs, ys;
    for (const V3& p : uniq) {
      xs.push_back(Dot(p - c, u));
      ys.push_back(Dot(p - c, v));
    }
    CircleFit cf;
    if (FitCircle2D(xs, ys, cf) && cf.r < 1e4 * len) {
      const double ctol = std::max(g.tol, 0.005 * cf.r);
      if (planeDev <= ctol && cf.maxDev <= ctol) {
        const V3 center = c + u * cf.cx + v * cf.cy;
        // Taranan açı
        double sweep = 0;
        for (size_t i = 0; i + 1 < m; ++i) {
          const V3 a = f.pts[i] - center, b = f.pts[i + 1] - center;
          sweep += std::atan2(Len(Cross(a, b)), Dot(a, b));
        }
        info.kind = GeomKind::Circle;
        info.radius = cf.r;
        ToArray(center, info.center);
        ToArray(n, info.dir);
        info.length = f.closed ? 2 * kPi * cf.r : std::min(sweep, 2 * kPi) * cf.r;
        return true;
      }
    }
  }
  return true;
}

void FaceTriangles(const MeshGeometry& g, int id, size_t& t0, size_t& t1) {
  t0 = t1 = 0;
  if (id < 0 || static_cast<size_t>(id) + 1 >= g.faceOffsets.size()) return;
  t0 = static_cast<size_t>(g.faceOffsets[id]);
  t1 = static_cast<size_t>(g.faceOffsets[id + 1]);
}

bool FitFace(const MeshGeometry& g, int id, ShapeInfo& info) {
  info = ShapeInfo();
  size_t t0, t1;
  FaceTriangles(g, id, t0, t1);
  if (t1 <= t0) return false;

  double area = 0;
  V3 centroid, nsum;
  double M[3][3] = {};
  std::vector<uint32_t> verts;
  verts.reserve((t1 - t0) * 3);
  std::vector<V3> normals;
  std::vector<double> areas;
  for (size_t t = t0; t < t1; ++t) {
    const uint32_t ia = g.tris[3 * t], ib = g.tris[3 * t + 1], ic = g.tris[3 * t + 2];
    const V3 a = PosOf(g, ia), b = PosOf(g, ib), c = PosOf(g, ic);
    const V3 cr = Cross(b - a, c - a);
    const double l = Len(cr);
    if (!(l > 0)) continue;
    const double ar = l * 0.5;
    const V3 n = cr * (1.0 / l);
    area += ar;
    centroid += (a + b + c) * (ar / 3.0);
    nsum += n * ar;
    const double nn[3] = {n.x, n.y, n.z};
    for (int i = 0; i < 3; ++i)
      for (int j = 0; j < 3; ++j) M[i][j] += ar * nn[i] * nn[j];
    normals.push_back(n);
    areas.push_back(ar);
    verts.push_back(ia);
    verts.push_back(ib);
    verts.push_back(ic);
  }
  if (!(area > 0)) return false;
  info.area = area;
  centroid = centroid * (1.0 / area);
  std::sort(verts.begin(), verts.end());
  verts.erase(std::unique(verts.begin(), verts.end()), verts.end());
  std::vector<V3> pts;
  pts.reserve(verts.size());
  for (uint32_t v : verts) pts.push_back(PosOf(g, v));
  double extent = 0;
  for (const V3& p : pts) extent = std::max(extent, Len(p - centroid));
  const double tol = std::max(g.tol, 1e-5 * extent);

  // Düzlem
  const V3 nAvg = Unit(nsum);
  if (Len(nsum) >= 0.9999 * area) {
    double dev = 0;
    for (const V3& p : pts) dev = std::max(dev, std::fabs(Dot(p - centroid, nAvg)));
    if (dev <= std::max(tol, 1e-4 * extent)) {
      info.kind = GeomKind::Plane;
      ToArray(centroid, info.center);
      ToArray(nAvg, info.dir);
      return true;
    }
  }

  // Silindir: tüm normaller eksene dik.
  if (pts.size() >= 4) {
    double ev[3];
    V3 vec[3];
    Eigen3(M, ev, vec);
    const V3 axis = vec[0];
    double maxDot = 0;
    for (const V3& n : normals) maxDot = std::max(maxDot, std::fabs(Dot(n, axis)));
    if (maxDot < 0.02 && ev[1] > 0.02 * area) {
      V3 u = Unit(Cross(axis, std::fabs(axis.x) < 0.9 ? V3(1, 0, 0) : V3(0, 1, 0)));
      V3 v = Unit(Cross(axis, u));
      std::vector<double> xs, ys;
      for (const V3& p : pts) {
        xs.push_back(Dot(p - centroid, u));
        ys.push_back(Dot(p - centroid, v));
      }
      CircleFit cf;
      if (FitCircle2D(xs, ys, cf) && cf.maxDev <= std::max(tol, 0.01 * cf.r)) {
        info.kind = GeomKind::Cylinder;
        info.radius = cf.r;
        ToArray(centroid + u * cf.cx + v * cf.cy, info.center);
        ToArray(axis, info.dir);
        return true;
      }
    }
  }

  // Küre
  if (pts.size() >= 8) {
    double m[4][5] = {};
    for (const V3& p0 : pts) {
      const V3 p = p0 - centroid;
      const double row[4] = {p.x, p.y, p.z, 1.0};
      const double rhs = -(p.x * p.x + p.y * p.y + p.z * p.z);
      for (int i = 0; i < 4; ++i) {
        for (int j = 0; j < 4; ++j) m[i][j] += row[i] * row[j];
        m[i][4] += row[i] * rhs;
      }
    }
    double s[4];
    if (Solve(4, m, s)) {
      const V3 c(-s[0] / 2, -s[1] / 2, -s[2] / 2);
      const double r2 = Dot(c, c) - s[3];
      if (r2 > 0) {
        const double r = std::sqrt(r2);
        double dev = 0;
        for (const V3& p : pts) dev = std::max(dev, std::fabs(Len(p - centroid - c) - r));
        if (dev <= std::max(tol, 0.01 * r) && r < 10 * extent) {
          info.kind = GeomKind::Sphere;
          info.radius = r;
          ToArray(centroid + c, info.center);
          return true;
        }
      }
    }
  }

  info.kind = GeomKind::Surface;
  ToArray(centroid, info.center);
  return true;
}

TopoDS_Shape EdgeShape(const EdgeFit& f) {
  const ShapeInfo& i = f.info;
  const V3 p0 = f.pts.front(), p1 = f.pts.back();
  if (i.kind == GeomKind::Line) {
    return BRepBuilderAPI_MakeEdge(ToPnt(p0), ToPnt(p1)).Edge();
  }
  if (i.kind == GeomKind::Circle) {
    const gp_Pnt c(i.center[0], i.center[1], i.center[2]);
    const gp_Dir d(i.dir[0], i.dir[1], i.dir[2]);
    if (f.closed) return BRepBuilderAPI_MakeEdge(gp_Circ(gp_Ax2(c, d), i.radius)).Edge();
    const V3 pm = f.pts[f.pts.size() / 2];
    GC_MakeArcOfCircle arc(ToPnt(p0), ToPnt(pm), ToPnt(p1));
    if (arc.IsDone()) return BRepBuilderAPI_MakeEdge(arc.Value()).Edge();
  }
  BRepBuilderAPI_MakePolygon poly;
  for (const V3& p : f.pts) poly.Add(ToPnt(p));
  if (poly.IsDone()) return poly.Wire();
  return BRepBuilderAPI_MakeEdge(ToPnt(p0), ToPnt(p1)).Edge();
}

TopoDS_Shape FaceShape(const MeshGeometry& g, int id) {
  size_t t0, t1;
  FaceTriangles(g, id, t0, t1);
  TopoDS_Compound comp;
  BRep_Builder builder;
  builder.MakeCompound(comp);
  if (t1 - t0 <= kMaxMeasureTriangles) {
    for (size_t t = t0; t < t1; ++t) {
      const gp_Pnt a = ToPnt(PosOf(g, g.tris[3 * t])), b = ToPnt(PosOf(g, g.tris[3 * t + 1])),
                   c = ToPnt(PosOf(g, g.tris[3 * t + 2]));
      BRepBuilderAPI_MakePolygon poly(a, b, c, /*Close=*/true);
      if (!poly.IsDone()) continue;
      BRepBuilderAPI_MakeFace face(poly.Wire(), /*OnlyPlane=*/true);
      if (face.IsDone()) builder.Add(comp, face.Face());
    }
  } else {
    // Çok büyük yüzey: köşe noktalarından örnek al (yaklaşık mesafe).
    std::vector<uint32_t> verts(g.tris.begin() + static_cast<long>(3 * t0),
                                g.tris.begin() + static_cast<long>(3 * t1));
    std::sort(verts.begin(), verts.end());
    verts.erase(std::unique(verts.begin(), verts.end()), verts.end());
    const size_t step = std::max<size_t>(1, verts.size() / (kMaxMeasureTriangles * 2));
    for (size_t i = 0; i < verts.size(); i += step) {
      builder.Add(comp, BRepBuilderAPI_MakeVertex(ToPnt(PosOf(g, verts[i]))).Vertex());
    }
  }
  return comp;
}

}  // namespace

// ---------------------------------------------------------------------------

bool BuildMeshModel(RawMesh& raw, MeshData& out, std::string& error) {
  for (auto* v : {&out.faceOffsets, &out.edgeOffsets, &out.partFaceOffsets, &out.partEdgeOffsets}) {
    if (v->empty()) v->push_back(0);
  }
  double bmin[3] = {1e300, 1e300, 1e300}, bmax[3] = {-1e300, -1e300, -1e300};
  size_t nPos = 0;
  for (const RawPart& p : raw.parts) {
    for (size_t i = 0; i + 2 < p.positions.size(); i += 3) {
      for (int k = 0; k < 3; ++k) {
        bmin[k] = std::min(bmin[k], static_cast<double>(p.positions[i + k]));
        bmax[k] = std::max(bmax[k], static_cast<double>(p.positions[i + k]));
      }
      ++nPos;
    }
  }
  if (nPos == 0) {
    error = "no_geometry";
    return false;
  }
  const double diag = std::sqrt((bmax[0] - bmin[0]) * (bmax[0] - bmin[0]) +
                                (bmax[1] - bmin[1]) * (bmax[1] - bmin[1]) +
                                (bmax[2] - bmin[2]) * (bmax[2] - bmin[2]));
  // Kaynaştırma toleransı: float hassasiyetinin biraz üstü.
  double maxAbs = 0;
  for (int k = 0; k < 3; ++k) maxAbs = std::max({maxAbs, std::fabs(bmin[k]), std::fabs(bmax[k])});
  const double weldEps = std::max({diag * 1e-7, maxAbs * 1e-7, 1e-9});

  auto geom = std::make_shared<MeshGeometry>();
  MeshGeometry& g = *geom;
  g.tol = std::max(diag * 1e-5, weldEps * 10);
  g.faceOffsets.assign(1, 0);
  g.partFaceOffsets.assign(1, 0);
  g.edgePtOffsets.assign(1, 0);

  for (RawPart& part : raw.parts) {
    ProcessPart(part, raw.colors, weldEps, g.tol, g, out);
  }
  std::vector<RawPart>().swap(raw.parts);

  out.tree = std::move(raw.tree);
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
  float fmin[3] = {1e30f, 1e30f, 1e30f}, fmax[3] = {-1e30f, -1e30f, -1e30f};
  for (size_t v = 0; v < out.vertexCount(); ++v) {
    for (int k = 0; k < 3; ++k) {
      const float c = out.vertices[v * kFloatsPerVertex + k];
      fmin[k] = std::min(fmin[k], c);
      fmax[k] = std::max(fmax[k], c);
    }
  }
  for (int k = 0; k < 3; ++k) {
    out.bboxMin[k] = fmin[k];
    out.bboxMax[k] = fmax[k];
  }
  out.meshGeometry = std::move(geom);
  return true;
}

bool MeshFeatureInfo(const MeshGeometry& g, int kind, int id, ShapeInfo& info) {
  info = ShapeInfo();
  if (kind == 0) {
    EdgeFit f;
    if (!FitEdge(g, id, f)) return false;
    info = f.info;
    return true;
  }
  if (kind == 1) return FitFace(g, id, info);
  return false;
}

bool MeshMeasurePair(const MeshGeometry& g, int kindA, int idA, int kindB, int idB,
                     PairMeasure& out) {
  out = PairMeasure();
  try {
    ShapeInfo infos[2];
    TopoDS_Shape shapes[2];
    const int kinds[2] = {kindA, kindB}, ids[2] = {idA, idB};
    for (int s = 0; s < 2; ++s) {
      if (kinds[s] == 0) {
        EdgeFit f;
        if (!FitEdge(g, ids[s], f)) return false;
        infos[s] = f.info;
        shapes[s] = EdgeShape(f);
      } else {
        if (!FitFace(g, ids[s], infos[s])) return false;
        shapes[s] = FaceShape(g, ids[s]);
      }
      if (shapes[s].IsNull()) return false;
    }
    return MeasurePairWithInfo(shapes[0], &infos[0], shapes[1], &infos[1], out);
  } catch (const Standard_Failure&) {
    return false;
  }
}

bool MeshMassProps(const MeshGeometry& g, const std::vector<int>* partIds, MassProps& out) {
  out = MassProps();
  const int nParts = static_cast<int>(g.partFaceOffsets.size()) - 1;
  std::vector<int> all;
  if (partIds == nullptr) {
    all.resize(static_cast<size_t>(std::max(nParts, 0)));
    std::iota(all.begin(), all.end(), 0);
    partIds = &all;
  }
  double area = 0, volume = 0;
  V3 areaMoment, volMoment;
  for (int p : *partIds) {
    if (p < 0 || p >= nParts) continue;
    const size_t t0 = static_cast<size_t>(g.faceOffsets[g.partFaceOffsets[p]]);
    const size_t t1 = static_cast<size_t>(g.faceOffsets[g.partFaceOffsets[p + 1]]);
    double vol = 0;
    V3 vm;
    for (size_t t = t0; t < t1; ++t) {
      const V3 a = PosOf(g, g.tris[3 * t]), b = PosOf(g, g.tris[3 * t + 1]),
               c = PosOf(g, g.tris[3 * t + 2]);
      const double ar = 0.5 * Len(Cross(b - a, c - a));
      area += ar;
      areaMoment += (a + b + c) * (ar / 3.0);
      const double v6 = Dot(a, Cross(b, c));  // orijinle oluşan dörtyüzlünün 6 katı hacmi
      vol += v6;
      vm += (a + b + c) * v6;  // dörtyüzlü merkezi (a+b+c+0)/4
    }
    if (g.partClosed[static_cast<size_t>(p)] && vol != 0) {
      // İçi dışına dönük parçalarda işaret ters çıkar; mutlak değer alınır.
      const double v = std::fabs(vol) / 6.0;
      const V3 c = vm * (1.0 / (4.0 * vol));
      volume += v;
      volMoment += c * v;
      out.hasSolids = true;
    }
  }
  out.area = area;
  out.volume = volume;
  if (out.hasSolids && volume > 0) {
    ToArray(volMoment * (1.0 / volume), out.center);
  } else if (area > 0) {
    ToArray(areaMoment * (1.0 / area), out.center);
  }
  return area > 0;
}

}  // namespace stepviewer
