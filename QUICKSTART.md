# 🚀 Быстрая памятка

## ✅ Система обновлений настроена!

**Ваш репозиторий**: https://github.com/pluxel0102/DiceAutoBet
**Токен**: `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`

---

## 📝 Что дальше (по порядку):

### 1️⃣ Загрузите код в GitHub
```powershell
cd C:\Users\User\AndroidStudioProjects\DiceAutoBet
git init
git add .
git commit -m "Initial commit: DiceAutoBet v1.0.0"
git branch -M main
git remote add origin https://github.com/pluxel0102/DiceAutoBet.git
git push -u origin main
```
Логин: `pluxel0102`  
Пароль: `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`

---

### 2️⃣ Соберите Release APK
**Build** → **Generate Signed Bundle / APK** → **APK** → **Create new keystore**

**⚠️ СОХРАНИТЕ KEYSTORE И ПАРОЛЬ!**

---

### 3️⃣ Создайте релиз на GitHub
1. https://github.com/pluxel0102/DiceAutoBet/releases
2. **Create a new release**
3. Tag: `v1.0.0`
4. Загрузите `app-release.apk`
5. **Publish release**

---

### 4️⃣ Проверьте работу
Установите APK → Запустите → Должно появиться:
```
✅ Версия актуальна: 1.0.0
```

---

## 📚 Полные инструкции:
- `SETUP_GITHUB.md` - подробное пошаговое руководство
- `UPDATE_SYSTEM.md` - документация системы обновлений
- `RELEASE_GUIDE.md` - как делать новые релизы

---

## 🔐 Важно:
- ✅ Токен в коде - это нормально (приватный репозиторий)
- ⚠️ НЕ загружайте keystore в Git!
- 💾 Сохраните keystore в безопасном месте

---

**Следующий раз:**
```powershell
# Обновите versionCode в build.gradle.kts
# Соберите новый APK (тем же keystore!)
# Создайте релиз v1.0.1 на GitHub
# Обновите update.json
git add update.json
git commit -m "Update to v1.0.1"
git push
```

Готово! 🎉
