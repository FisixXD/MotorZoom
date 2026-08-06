use jni::{
    objects::{JByteArray, JClass, JString},
    sys::{jboolean, jint, JNI_FALSE, JNI_TRUE},
    JNIEnv,
};
use ntsc_rs::{
    settings::{SettingsList, standard::NtscEffect},
    yiq_fielding::Rgbx,
    Context,
};
use std::sync::{Mutex, OnceLock};

struct Engine {
    context: Context,
    effect: NtscEffect,
}

static ENGINE: OnceLock<Mutex<Engine>> = OnceLock::new();

fn engine() -> &'static Mutex<Engine> {
    ENGINE.get_or_init(|| {
        Mutex::new(Engine {
            context: Context::new(),
            effect: NtscEffect::default(),
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_motorzoom_NativeNtsc_configure(
    mut env: JNIEnv,
    _class: JClass,
    preset: JString,
) -> jboolean {
    let Ok(text) = env.get_string(&preset).map(|s| s.into()) else {
        return JNI_FALSE;
    };
    let effect = if text.trim().is_empty() {
        NtscEffect::default()
    } else {
        match SettingsList::<NtscEffect>::new().from_json(&text) {
            Ok(value) => value,
            Err(_) => return JNI_FALSE,
        }
    };
    engine().lock().unwrap().effect = effect;
    JNI_TRUE
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_motorzoom_NativeNtsc_processRgba(
    env: JNIEnv,
    _class: JClass,
    pixels: JByteArray,
    width: jint,
    height: jint,
    frame_number: jint,
) -> jboolean {
    if width <= 0 || height <= 0 {
        return JNI_FALSE;
    }
    let expected = width as usize * height as usize * 4;
    let Ok(mut bytes) = env.convert_byte_array(&pixels) else {
        return JNI_FALSE;
    };
    if bytes.len() != expected {
        return JNI_FALSE;
    }
    {
        let locked = engine().lock().unwrap();
        locked.effect.apply_effect_to_buffer::<Rgbx, u8>(
            &locked.context,
            (width as usize, height as usize),
            &mut bytes,
            frame_number.max(0) as usize,
            [1.0, 1.0],
        );
    }
    let signed: Vec<i8> = bytes.into_iter().map(|byte| byte as i8).collect();
    if env.set_byte_array_region(&pixels, 0, &signed).is_err() {
        return JNI_FALSE;
    }
    JNI_TRUE
}
