use jni::{
    objects::{JByteArray, JClass, JString},
    sys::{jboolean, jint, jstring, JNI_FALSE, JNI_TRUE},
    JNIEnv,
};
use ntsc_rs::{
    settings::SettingsList,
    yiq_fielding::Rgbx,
    NtscEffect, NtscEffectFullSettings,
};
use std::sync::{Mutex, OnceLock};

struct Engine {
    effect: NtscEffect,
}

fn string_to_java(env: &mut JNIEnv, value: String) -> jstring {
    env.new_string(value)
        .map(|text| text.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_motorzoom_NativeNtsc_defaultPreset(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let list = SettingsList::<NtscEffectFullSettings>::new();
    let json = list
        .to_json_string(&NtscEffectFullSettings::default())
        .unwrap_or_else(|_| "{\"version\":1}".to_owned());
    string_to_java(&mut env, json)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_motorzoom_NativeNtsc_normalizePreset(
    mut env: JNIEnv,
    _class: JClass,
    preset: JString,
) -> jstring {
    let text: String = match env.get_string(&preset) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let list = SettingsList::<NtscEffectFullSettings>::new();
    let Ok(settings) = list.from_json(&text) else {
        return std::ptr::null_mut();
    };
    match list.to_json_string(&settings) {
        Ok(json) => string_to_java(&mut env, json),
        Err(_) => std::ptr::null_mut(),
    }
}

static ENGINE: OnceLock<Mutex<Engine>> = OnceLock::new();

fn engine() -> &'static Mutex<Engine> {
    ENGINE.get_or_init(|| {
        Mutex::new(Engine {
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
    let text: String = match env.get_string(&preset) {
        Ok(value) => value.into(),
        Err(_) => return JNI_FALSE,
    };
    let effect = if text.trim().is_empty() {
        NtscEffect::default()
    } else {
        match SettingsList::<NtscEffectFullSettings>::new().from_json(&text) {
            Ok(value) => (&value).into(),
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
