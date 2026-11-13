from flask import Flask, request, jsonify
import torch
import torch.nn as nn
import torch.nn.functional as F
from torchvision.models import resnet18
from torchvision import transforms
from PIL import Image
import io
import base64
import os

app = Flask(__name__)

class DualStreamResNet18(nn.Module):
    def __init__(self, num_classes=7):
        super().__init__()
        vis_net = resnet18(weights=None)
        uv_net  = resnet18(weights=None)
        self.visible_backbone = nn.Sequential(*list(vis_net.children())[:-1])
        self.uv_backbone      = nn.Sequential(*list(uv_net.children())[:-1])
        feat_dim = 512
        self.vis_attention = nn.Sequential(nn.Linear(feat_dim, feat_dim), nn.Sigmoid())
        self.uv_attention  = nn.Sequential(nn.Linear(feat_dim, feat_dim), nn.Sigmoid())
        self.classifier = nn.Sequential(
            nn.Linear(feat_dim, 256),
            nn.BatchNorm1d(256),
            nn.ReLU(),
            nn.Dropout(0.4),
            nn.Linear(256, num_classes)
        )
        self.embedding_head = nn.Sequential(
            nn.Linear(feat_dim, 128),
            nn.ReLU(),
            nn.Linear(128, 128)
        )

    def extract_features(self, visible, uv):
        vis_f = self.visible_backbone(visible).flatten(1)
        uv_f  = self.uv_backbone(uv).flatten(1)
        vis_att = self.vis_attention(uv_f)
        uv_att  = self.uv_attention(vis_f)
        return (vis_f * vis_att) + (uv_f * uv_att)

    def forward(self, visible, uv):
        return self.classifier(self.extract_features(visible, uv))

    def get_embedding(self, visible, uv):
        return F.normalize(
            self.embedding_head(self.extract_features(visible, uv)), p=2, dim=1
        )

CLASS_NAMES = {
    0: "Melanocytic Nevi",
    1: "Melanoma",
    2: "Benign Keratosis",
    3: "Basal Cell Carcinoma",
    4: "Actinic Keratoses",
    5: "Vascular Lesions",
    6: "Dermatofibroma"
}

transform = transforms.Compose([
    transforms.Resize((224, 224)),
    transforms.ToTensor(),
    transforms.Normalize([0.485, 0.456, 0.406],
                         [0.229, 0.224, 0.225])
])

print("Loading model...")
model = DualStreamResNet18(num_classes=7)
# Path to the trained weights (set MODEL_PATH, or put best_model.pth next to this file)
MODEL_PATH = os.environ.get('MODEL_PATH', os.path.join(os.path.dirname(__file__), 'best_model.pth'))
model.load_state_dict(torch.load(MODEL_PATH, map_location='cpu'))
model.eval()
print("Model loaded!")

@app.route('/predict', methods=['POST'])
def predict():
    try:
        data = request.json
        vis_bytes = base64.b64decode(data['visible'])
        uv_bytes  = base64.b64decode(data['uv'])
        vis_img = Image.open(io.BytesIO(vis_bytes)).convert('RGB')
        uv_img  = Image.open(io.BytesIO(uv_bytes)).convert('RGB')
        vis_tensor = transform(vis_img).unsqueeze(0)
        uv_tensor  = transform(uv_img).unsqueeze(0)
        with torch.no_grad():
            output = model(vis_tensor, uv_tensor)
        probs   = torch.softmax(output[0], dim=0)
        max_idx = probs.argmax().item()
        all_scores = {CLASS_NAMES[i]: round(probs[i].item() * 100, 1) for i in range(7)}
        return jsonify({
            'success':    True,
            'diagnosis':  CLASS_NAMES[max_idx],
            'confidence': round(probs[max_idx].item() * 100, 1),
            'all_scores': all_scores
        })
    except Exception as e:
        return jsonify({'success': False, 'error': str(e)})

@app.route('/health', methods=['GET'])
def health():
    return jsonify({'status': 'ok'})

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, debug=False)
