// Mesh dosyalarını Assimp ile okuyup RawMesh'e çevirir.
// Birimler mm'ye, eksenler CAD alışkanlığına (Z yukarı) dönüştürülür.
#include "mesh_model.h"

#ifdef STEPVIEWER_NO_ASSIMP

namespace stepviewer {
bool ImportMeshFile(const std::string&, FileFormat, RawMesh&, std::string& error) {
  error = "unsupported_format";
  return false;
}
}  // namespace stepviewer

#else

#include <assimp/Importer.hpp>
#include <assimp/config.h>
#include <assimp/material.h>
#include <assimp/postprocess.h>
#include <assimp/scene.h>

#include <cmath>
#include <cstring>

namespace stepviewer {
namespace {

constexpr float kDefault[3] = {0.70f, 0.72f, 0.75f};

const char* FormatName(FileFormat f) {
  switch (f) {
    case FileFormat::Obj: return "OBJ";
    case FileFormat::Stl: return "STL";
    case FileFormat::Gltf: return "glTF";
    case FileFormat::Dae: return "DAE";
    case FileFormat::Max3ds: return "3DS";
    case FileFormat::Fbx: return "FBX";
    case FileFormat::Ply: return "PLY";
    case FileFormat::ThreeMf: return "3MF";
    case FileFormat::Amf: return "AMF";
    case FileFormat::Off: return "OFF";
    default: return "?";
  }
}

float LinearToSrgb(float c) {
  c = std::fmin(std::fmax(c, 0.f), 1.f);
  return c <= 0.0031308f ? c * 12.92f : 1.055f * std::pow(c, 1.f / 2.4f) - 0.055f;
}

std::array<float, 3> MaterialColor(const aiMaterial* m, bool linear) {
  std::array<float, 3> out = {kDefault[0], kDefault[1], kDefault[2]};
  if (m == nullptr) return out;
  aiString name;
  if (m->Get(AI_MATKEY_NAME, name) == AI_SUCCESS &&
      std::strcmp(name.C_Str(), AI_DEFAULT_MATERIAL_NAME) == 0) {
    return out;
  }
  aiColor4D c;
  bool found = m->Get(AI_MATKEY_BASE_COLOR, c) == AI_SUCCESS;
  if (!found) found = m->Get(AI_MATKEY_COLOR_DIFFUSE, c) == AI_SUCCESS;
  if (!found) return out;
  // Dokulu malzemelerde renk çoğu zaman beyazdır; dokuyu göstermediğimiz için griye düş.
  const bool textured = m->GetTextureCount(aiTextureType_DIFFUSE) > 0 ||
                        m->GetTextureCount(aiTextureType_BASE_COLOR) > 0;
  if (textured && c.r > 0.98f && c.g > 0.98f && c.b > 0.98f) return out;
  out = {c.r, c.g, c.b};
  if (linear) {
    for (float& v : out) v = LinearToSrgb(v);
  }
  for (float& v : out) v = std::fmin(std::fmax(v, 0.f), 1.f);
  return out;
}

std::string CleanName(const char* raw) {
  std::string s = raw != nullptr ? raw : "";
  // Assimp'in yardımcı adları
  if (s.find("$AssimpFbx$") != std::string::npos) s = s.substr(0, s.find("_$AssimpFbx$"));
  if (s.rfind("$ColladaAutoName$", 0) == 0 || s == "RootNode" || s == "<3DSRoot>" ||
      s == "Root" || s == "defaultobject") {
    return std::string();
  }
  const auto first = s.find_first_not_of(" \t\r\n");
  if (first == std::string::npos) return std::string();
  const auto last = s.find_last_not_of(" \t\r\n");
  return s.substr(first, last - first + 1);
}

class SceneWalker {
 public:
  SceneWalker(const aiScene* scene, RawMesh& out) : scene_(scene), out_(out) {}

  void Walk(const aiNode* node, const aiMatrix4x4& parent, int depth) {
    const aiMatrix4x4 world = parent * node->mTransformation;
    const unsigned geomKids = GeometricChildren(node);
    const bool hasMeshes = HasTriangles(node);
    if (!hasMeshes && geomKids == 0) return;

    std::string name = CleanName(node->mName.C_Str());
    if (!hasMeshes && geomKids == 1) {
      // Tek çocuklu ara düğümleri atla (ağaç gereksiz derinleşmesin); adı çocuğa aktar.
      for (unsigned i = 0; i < node->mNumChildren; ++i) {
        const aiNode* child = node->mChildren[i];
        if (!SubtreeHasGeometry(child)) continue;
        if (!name.empty() && CleanName(child->mName.C_Str()).empty()) pendingName_ = name;
        Walk(child, world, depth);
      }
      return;
    }
    if (name.empty() && !pendingName_.empty()) name = pendingName_;
    pendingName_.clear();
    if (name.empty() && node->mNumMeshes == 1) {
      name = CleanName(scene_->mMeshes[node->mMeshes[0]]->mName.C_Str());
    }

    if (hasMeshes && geomKids == 0) {
      AddLeaf(node, world, name, depth);
      return;
    }
    TreeNode asmNode;
    asmNode.name = name;
    asmNode.depth = depth;
    asmNode.isAssembly = true;
    asmNode.leafStart = static_cast<int>(out_.parts.size());
    out_.tree.push_back(asmNode);
    if (hasMeshes) AddLeaf(node, world, name, depth + 1);
    for (unsigned i = 0; i < node->mNumChildren; ++i) Walk(node->mChildren[i], world, depth + 1);
  }

 private:
  bool MeshHasTriangles(const aiMesh* m) const {
    return m != nullptr && m->mNumVertices > 0 && (m->mPrimitiveTypes & aiPrimitiveType_TRIANGLE);
  }
  bool HasTriangles(const aiNode* n) const {
    for (unsigned i = 0; i < n->mNumMeshes; ++i) {
      if (MeshHasTriangles(scene_->mMeshes[n->mMeshes[i]])) return true;
    }
    return false;
  }
  bool SubtreeHasGeometry(const aiNode* n) const {
    if (HasTriangles(n)) return true;
    for (unsigned i = 0; i < n->mNumChildren; ++i) {
      if (SubtreeHasGeometry(n->mChildren[i])) return true;
    }
    return false;
  }
  unsigned GeometricChildren(const aiNode* n) const {
    unsigned c = 0;
    for (unsigned i = 0; i < n->mNumChildren; ++i) {
      if (SubtreeHasGeometry(n->mChildren[i])) ++c;
    }
    return c;
  }

  void AddLeaf(const aiNode* node, const aiMatrix4x4& world, const std::string& name, int depth) {
    TreeNode leaf;
    leaf.name = name;
    leaf.depth = depth;
    leaf.isAssembly = false;
    leaf.leafStart = static_cast<int>(out_.parts.size());
    out_.tree.push_back(leaf);

    RawPart part;
    for (unsigned i = 0; i < node->mNumMeshes; ++i) {
      const aiMesh* m = scene_->mMeshes[node->mMeshes[i]];
      if (!MeshHasTriangles(m)) continue;
      const uint32_t base = static_cast<uint32_t>(part.positions.size() / 3);
      part.positions.reserve(part.positions.size() + m->mNumVertices * 3);
      for (unsigned v = 0; v < m->mNumVertices; ++v) {
        const aiVector3D p = world * m->mVertices[v];
        part.positions.push_back(p.x);
        part.positions.push_back(p.y);
        part.positions.push_back(p.z);
      }
      for (unsigned f = 0; f < m->mNumFaces; ++f) {
        const aiFace& face = m->mFaces[f];
        if (face.mNumIndices != 3) continue;
        part.indices.push_back(base + face.mIndices[0]);
        part.indices.push_back(base + face.mIndices[1]);
        part.indices.push_back(base + face.mIndices[2]);
        part.triMaterial.push_back(m->mMaterialIndex);
      }
    }
    out_.parts.push_back(std::move(part));
  }

  const aiScene* scene_;
  RawMesh& out_;
  std::string pendingName_;
};

}  // namespace

bool ImportMeshFile(const std::string& path, FileFormat format, RawMesh& out,
                    std::string& error) {
  const std::string fmtName = FormatName(format);
  Assimp::Importer importer;
  // Nokta ve çizgi öğelerini at; yalnızca konumlar lazım (normaller vb. bellekte yer kaplamasın).
  importer.SetPropertyInteger(AI_CONFIG_PP_SBP_REMOVE, aiPrimitiveType_POINT | aiPrimitiveType_LINE);
  importer.SetPropertyInteger(
      AI_CONFIG_PP_RVC_FLAGS,
      aiComponent_NORMALS | aiComponent_TANGENTS_AND_BITANGENTS | aiComponent_COLORS |
          aiComponent_TEXCOORDS | aiComponent_BONEWEIGHTS | aiComponent_ANIMATIONS |
          aiComponent_LIGHTS | aiComponent_CAMERAS | aiComponent_TEXTURES);
  const unsigned flags =
      aiProcess_Triangulate | aiProcess_SortByPType | aiProcess_RemoveComponent;
  const aiScene* scene = importer.ReadFile(path, flags);
  if (scene == nullptr || scene->mRootNode == nullptr) {
    error = "read_failed:" + fmtName;
    return false;
  }
  if (scene->mNumMeshes == 0) {
    error = "no_geometry";
    return false;
  }

  // Birim ve eksen: Assimp glTF/DAE'yi metre, FBX'i santimetre olarak verir ve bunları Y yukarı
  // çevirir. OBJ, 3DS, STL, PLY ve OFF'ta birim bilgisi yoktur; 1 birim = 1 mm kabul edilir.
  // 3MF ve AMF varsayılan olarak mm ve Z yukarıdır; olduğu gibi kullanılır.
  double scale = 1.0;
  bool yUp = false;
  switch (format) {
    case FileFormat::Gltf: scale = 1000.0; yUp = true; break;
    case FileFormat::Dae: scale = 1000.0; yUp = true; break;
    case FileFormat::Fbx: scale = 10.0; yUp = true; break;
    case FileFormat::Obj: yUp = true; break;
    case FileFormat::Max3ds: yUp = true; break;
    default: break;
  }
  const float s = static_cast<float>(scale);
  aiMatrix4x4 root;  // birim matris
  if (yUp) {
    // (x, y, z) -> (x, -z, y): Y yukarı olan sahneyi Z yukarı yap.
    root = aiMatrix4x4(s, 0, 0, 0,
                       0, 0, -s, 0,
                       0, s, 0, 0,
                       0, 0, 0, 1);
  } else {
    root = aiMatrix4x4(s, 0, 0, 0,
                       0, s, 0, 0,
                       0, 0, s, 0,
                       0, 0, 0, 1);
  }

  const bool linear = format == FileFormat::Gltf;
  out.colors.clear();
  for (unsigned i = 0; i < scene->mNumMaterials; ++i) {
    out.colors.push_back(MaterialColor(scene->mMaterials[i], linear));
  }
  if (out.colors.empty()) out.colors.push_back({kDefault[0], kDefault[1], kDefault[2]});

  SceneWalker walker(scene, out);
  walker.Walk(scene->mRootNode, root, 0);
  if (out.parts.empty()) {
    error = "no_geometry";
    return false;
  }
  return true;
}

}  // namespace stepviewer

#endif  // STEPVIEWER_NO_ASSIMP
