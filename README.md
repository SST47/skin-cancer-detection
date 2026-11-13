# skin-cancer-detection

**Skinly: the future of AI powered skin diagnosis.**

Skin cancer dataset trained on UV. Skinly pairs a normal photo of a skin lesion with a photo under UV light, and a dual-stream CNN looks at both to sort the lesion into one of 7 types. A UV flashlight controlled by an Arduino, a box to hold the camera and light, and an Android app make up the rest of the system.

Built by Shrenik Subbaiyan with his group for the Grade 10 Science (SNC2D) culminating project at A.Y. Jackson Secondary School.

> **This is a student prototype, not a medical device.** It can't diagnose anything. See a doctor about any spot on your skin that worries you.

## The mind: the AI model

**Data.** [HAM10000](https://dataverse.harvard.edu/dataset.xhtml?persistentId=doi:10.7910/DVN/DBW86T) is an existing dataset with 10,015 images of 7 kinds of skin lesions. The major issue was that it did not contain UV sample images. Our intention was to train a dual-stream CNN that can take both UV and visible light, so we artificially simulated UV conditions to create a new UV skin cancer dataset. The `UVFluorescenceTransform` turns each normal dermoscopy image into a simulated Wood's lamp (365 nm) image.

**Architecture.** Training a model from scratch with completely random weights is extremely inefficient and unnecessary, so we used a ResNet-18 backbone to speed up training. Training on ResNet-18 is like teaching calculus to a middle schooler vs a baby. There are two ResNet-18s, one for the visible image and one for the UV image, and each one uses the other's features to decide what to focus on (cross-attention). The fused 512-dim features go to a classification head for the 7 classes, plus an embedding head for a prototypical extension that can add new lesion types from 5-10 images.

The 7 classes: melanocytic nevi, melanoma, benign keratosis, basal cell carcinoma, actinic keratoses, vascular lesions and dermatofibroma.

### Training: what went wrong and how we fixed it

1. **Model not learning.** On our first attempt, the model just was not learning: the loss and accuracy never actually improved. We attributed this to the slower T4 GPU on Google Colab, so we moved training to the local GPUs on Macs (MPS).
2. **Model cheating.** On our second attempt, the model did well. Too well, perhaps: 97% accuracy on the second epoch, with only 10 minutes of training. Looking through the code, we found that some classes were augmented differently. We gave each class its own UV colour shift, thinking it might help the model recognize the features. Instead, it just learned that some colours automatically meant some class (coral pink = actinic keratoses, yellow-green = benign keratosis, and so on).
3. **Class bias.** On our third try, the model finally learned in a healthy way. But nearly 67% of the dataset is melanocytic nevi (benign moles), so there was a bias towards that class. A weighted sampler balances the classes during training, and we switched the prediction architecture from prototypical to regular.

**Known issue.** The notebook in this repo still calls `UVFluorescenceTransform(lesion_type=lesion_type)` in the dataset, which applies the per-class colour shift from attempt 2. To train without the shortcut, pass `lesion_type=None` so every image gets the same UV simulation. Accuracy numbers from a run with the per-class shift on don't measure real performance.

## The body: hardware

- **Soldering the UV light.** The UV flashlight head was originally battery powered. We took it apart and unsoldered the spring connecting the positive point to the battery compartment, to make space for the positive wire. The negative wire ran through the spine of the body, so we used a multimeter to test each terminal on the flashlight's PCB to find where it was supposed to go.
- **Arduino control.** Connecting the flashlight to an Arduino let us switch the LED on and off, so we can take photos with the light on and off.
- **The house.** A box with opaque surfaces absorbs outside light, so there's less interference when the UV light shines in, and it doubles as a stand that holds the UV light and camera in place.

## Connecting the body with the mind: software

- **Hosting the model.** Our first approach was putting the model in the app, but the PyTorch version we trained with was too new for Android. Instead, a Flask server on a Mac runs the model and is tunneled through ngrok, so the app can reach it.
- **The app.** Built in Android Studio. It controls the UV flashlight and a normal flashlight, captures the visible and UV photos, sends them to the model as base64, and uses Gemini 2.5 to turn the model's output into a readable response. The phone camera couldn't adjust to the light quickly enough, so we made the delay between the two photos longer.

The Flask server, Android app and Arduino code aren't in this repo yet.

## Run it

The notebook runs in Google Colab and expects the HAM10000 images and `HAM10000_metadata.csv` in your Google Drive (`HAM10000_images_part_1`, `HAM10000_images_part_2`).

```bash
pip install -r requirements.txt
jupyter notebook notebooks/uv_detect.ipynb
```

## Files

- `notebooks/uv_detect.ipynb`: simulated UV dataset, dual-stream ResNet-18, training, evaluation and the prototypical extension
- `docs/skinly-design-blog.pdf`: our design process and challenges, from the SNC2D culminating blog

Built with help from AI tools.
