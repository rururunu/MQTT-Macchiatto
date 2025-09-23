// 国际化管理器
class I18nManager {
    constructor() {
        this.currentLang = 'zh';
        this.translations = {};
        this.init();
    }

    async init() {
        try {
            // 加载翻译文件
            const response = await fetch('i18n.json');
            this.translations = await response.json();
            
            // 从localStorage获取用户语言偏好
            const savedLang = localStorage.getItem('language');
            if (savedLang && this.translations[savedLang]) {
                this.currentLang = savedLang;
            }
            
            // 初始化页面
            this.updateLanguage();
            this.bindEvents();
        } catch (error) {
            console.error('Failed to load translations:', error);
        }
    }

    bindEvents() {
        // 语言切换按钮事件
        document.addEventListener('click', (e) => {
            const langBtn = e.target.closest('.lang-btn');
            if (langBtn) {
                const lang = langBtn.getAttribute('data-lang');
                if (lang && lang !== this.currentLang) {
                    this.switchLanguage(lang);
                }
            }
        });
    }

    switchLanguage(lang) {
        if (!this.translations[lang]) {
            console.error(`Language ${lang} not found`);
            return;
        }

        this.currentLang = lang;
        localStorage.setItem('language', lang);
        
        // 更新HTML lang属性
        document.documentElement.lang = lang === 'zh' ? 'zh-CN' : 'en';
        
        // 更新页面标题
        document.title = this.translations[lang].title;
        
        // 更新语言按钮状态
        this.updateLanguageButtons();
        
        // 更新页面内容
        this.updatePageContent();
    }

    updateLanguage() {
        // 更新HTML lang属性
        document.documentElement.lang = this.currentLang === 'zh' ? 'zh-CN' : 'en';
        
        // 更新页面标题
        document.title = this.translations[this.currentLang].title;
        
        // 更新语言按钮状态
        this.updateLanguageButtons();
        
        // 更新页面内容
        this.updatePageContent();
    }

    updateLanguageButtons() {
        const langBtns = document.querySelectorAll('.lang-btn');
        langBtns.forEach(btn => {
            const lang = btn.getAttribute('data-lang');
            btn.classList.toggle('active', lang === this.currentLang);
        });
    }

    updatePageContent() {
        const elements = document.querySelectorAll('[data-i18n]');
        elements.forEach(element => {
            const key = element.getAttribute('data-i18n');
            const translation = this.getTranslation(key);
            if (translation) {
                element.textContent = translation;
            }
        });
    }

    getTranslation(key) {
        const keys = key.split('.');
        let value = this.translations[this.currentLang];
        
        for (const k of keys) {
            if (value && typeof value === 'object') {
                value = value[k];
            } else {
                return null;
            }
        }
        
        return value;
    }
}

// 初始化国际化管理器
let i18nManager;
document.addEventListener('DOMContentLoaded', () => {
    i18nManager = new I18nManager();
});