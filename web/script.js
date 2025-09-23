// 页面加载完成后执行
document.addEventListener('DOMContentLoaded', function() {
    // 平滑滚动
    initSmoothScroll();
    
    // 标签页切换
    initTabs();
    
    // 导航栏滚动效果
    initNavbarScroll();
    
    // 初始化 Prism.js
    initPrismHighlight();
    
    // 动画效果
    initAnimations();
});

// 平滑滚动
function initSmoothScroll() {
    const links = document.querySelectorAll('a[href^="#"]');
    
    links.forEach(link => {
        link.addEventListener('click', function(e) {
            e.preventDefault();
            
            const targetId = this.getAttribute('href');
            const targetElement = document.querySelector(targetId);
            
            if (targetElement) {
                const offsetTop = targetElement.offsetTop - 100; // 考虑导航栏高度
                
                window.scrollTo({
                    top: offsetTop,
                    behavior: 'smooth'
                });
            }
        });
    });
}

// 标签页切换
function initTabs() {
    const tabButtons = document.querySelectorAll('.tab-btn');
    const tabPanels = document.querySelectorAll('.tab-panel');
    
    tabButtons.forEach(button => {
        button.addEventListener('click', function() {
            const targetTab = this.getAttribute('data-tab');
            
            // 移除所有活动状态
            tabButtons.forEach(btn => btn.classList.remove('active'));
            tabPanels.forEach(panel => panel.classList.remove('active'));
            
            // 添加活动状态
            this.classList.add('active');
            document.getElementById(targetTab).classList.add('active');
        });
    });
}

// 导航栏滚动效果
function initNavbarScroll() {
    const navbar = document.querySelector('.navbar');
    let lastScrollTop = 0;
    
    window.addEventListener('scroll', function() {
        const scrollTop = window.pageYOffset || document.documentElement.scrollTop;
        
        // 添加背景模糊效果
        if (scrollTop > 50) {
            navbar.style.background = 'linear-gradient(135deg, rgba(220, 38, 38, 0.95), rgba(185, 28, 28, 0.95))';
            navbar.style.backdropFilter = 'blur(10px)';
        } else {
            navbar.style.background = 'linear-gradient(135deg, #dc2626, #b91c1c)';
            navbar.style.backdropFilter = 'none';
        }
        
        lastScrollTop = scrollTop;
    });
}

// 初始化 Prism.js 语法高亮
function initPrismHighlight() {
    // 确保 Prism 已加载
    if (typeof Prism !== 'undefined') {
        // 重新高亮所有代码块
        Prism.highlightAll();
        
        // 为动态加载的内容添加高亮
        const observer = new MutationObserver(function(mutations) {
            mutations.forEach(function(mutation) {
                if (mutation.type === 'childList') {
                    mutation.addedNodes.forEach(function(node) {
                        if (node.nodeType === 1) { // Element node
                            const codeBlocks = node.querySelectorAll('pre[class*="language-"]');
                            codeBlocks.forEach(function(block) {
                                Prism.highlightElement(block);
                            });
                        }
                    });
                }
            });
        });
        
        observer.observe(document.body, {
            childList: true,
            subtree: true
        });
    } else {
        // 如果 Prism 还没加载，延迟执行
        setTimeout(initPrismHighlight, 100);
    }
}

// 动画效果
function initAnimations() {
    // 创建观察者
    const observer = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                entry.target.style.opacity = '1';
                entry.target.style.transform = 'translateY(0)';
            }
        });
    }, {
        threshold: 0.1,
        rootMargin: '0px 0px -50px 0px'
    });
    
    // 观察需要动画的元素
    const animatedElements = document.querySelectorAll('.feature-card, .step, .contact-item');
    
    animatedElements.forEach(el => {
        el.style.opacity = '0';
        el.style.transform = 'translateY(30px)';
        el.style.transition = 'opacity 0.6s ease, transform 0.6s ease';
        observer.observe(el);
    });
}

// 复制代码功能
function copyCode(button) {
    const codeBlock = button.nextElementSibling.querySelector('code');
    const text = codeBlock.textContent;
    
    navigator.clipboard.writeText(text).then(() => {
        const originalText = button.textContent;
        button.textContent = '已复制!';
        button.style.background = '#10b981';
        
        setTimeout(() => {
            button.textContent = originalText;
            button.style.background = '#dc2626';
        }, 2000);
    }).catch(err => {
        console.error('复制失败:', err);
    });
}

// 添加复制按钮到代码块
function initCopyButtons() {
    const codeBlocks = document.querySelectorAll('.code-block, .code-example, .code-content');
    
    codeBlocks.forEach(block => {
        // 避免重复添加按钮
        if (block.querySelector('.copy-btn')) return;
        
        const copyButton = document.createElement('button');
        copyButton.innerHTML = '<i class="fas fa-copy"></i> 复制代码';
        copyButton.className = 'copy-btn';
        copyButton.style.cssText = `
            position: absolute;
            top: 15px;
            right: 15px;
            background: #dc2626;
            color: white;
            border: none;
            padding: 8px 12px;
            border-radius: 6px;
            font-size: 0.8rem;
            cursor: pointer;
            display: flex;
            align-items: center;
            gap: 5px;
            transition: all 0.3s ease;
            z-index: 10;
            font-family: inherit;
        `;
        
        copyButton.addEventListener('mouseenter', function() {
            this.style.background = '#b91c1c';
            this.style.transform = 'translateY(-2px)';
        });
        
        copyButton.addEventListener('mouseleave', function() {
            this.style.background = '#dc2626';
            this.style.transform = 'translateY(0)';
        });
        
        copyButton.addEventListener('click', function() {
            const codeElement = block.querySelector('code');
            const text = codeElement.textContent || codeElement.innerText;
            
            navigator.clipboard.writeText(text).then(() => {
                this.innerHTML = '<i class="fas fa-check"></i> 已复制!';
                this.style.background = '#10b981';
                
                setTimeout(() => {
                    this.innerHTML = '<i class="fas fa-copy"></i> 复制代码';
                    this.style.background = '#dc2626';
                }, 2000);
            }).catch(err => {
                console.error('复制失败:', err);
                this.innerHTML = '<i class="fas fa-times"></i> 复制失败';
                this.style.background = '#ef4444';
                
                setTimeout(() => {
                    this.innerHTML = '<i class="fas fa-copy"></i> 复制代码';
                    this.style.background = '#dc2626';
                }, 2000);
            });
        });
        
        block.style.position = 'relative';
        block.appendChild(copyButton);
    });
}

// 在页面加载完成后初始化复制按钮
document.addEventListener('DOMContentLoaded', function() {
    // 延迟执行，确保 Prism.js 已经处理完代码
    setTimeout(initCopyButtons, 500);
});

// 页面滚动进度条
function initScrollProgress() {
    const progressBar = document.createElement('div');
    progressBar.style.cssText = `
        position: fixed;
        top: 100px;
        left: 0;
        width: 0%;
        height: 3px;
        background: linear-gradient(90deg, #dc2626, #b91c1c);
        z-index: 1001;
        transition: width 0.1s ease;
    `;
    document.body.appendChild(progressBar);
    
    window.addEventListener('scroll', function() {
        const scrollTop = window.pageYOffset;
        const docHeight = document.documentElement.scrollHeight - window.innerHeight;
        const scrollPercent = (scrollTop / docHeight) * 100;
        progressBar.style.width = scrollPercent + '%';
    });
}

// 初始化滚动进度条
document.addEventListener('DOMContentLoaded', initScrollProgress);

// 返回顶部按钮
function initBackToTop() {
    const backToTopBtn = document.createElement('button');
    backToTopBtn.innerHTML = '<i class="fas fa-arrow-up"></i>';
    backToTopBtn.className = 'back-to-top';
    backToTopBtn.style.cssText = `
        position: fixed;
        bottom: 30px;
        right: 30px;
        width: 50px;
        height: 50px;
        background: linear-gradient(135deg, #dc2626, #b91c1c);
        color: white;
        border: none;
        border-radius: 50%;
        font-size: 1.2rem;
        cursor: pointer;
        opacity: 0;
        visibility: hidden;
        transition: all 0.3s ease;
        z-index: 1000;
        box-shadow: 0 4px 15px rgba(220, 38, 38, 0.3);
    `;
    
    backToTopBtn.addEventListener('click', function() {
        window.scrollTo({
            top: 0,
            behavior: 'smooth'
        });
    });
    
    backToTopBtn.addEventListener('mouseenter', function() {
        this.style.transform = 'translateY(-3px) scale(1.1)';
        this.style.boxShadow = '0 8px 25px rgba(220, 38, 38, 0.4)';
    });
    
    backToTopBtn.addEventListener('mouseleave', function() {
        this.style.transform = 'translateY(0) scale(1)';
        this.style.boxShadow = '0 4px 15px rgba(220, 38, 38, 0.3)';
    });
    
    window.addEventListener('scroll', function() {
        if (window.pageYOffset > 300) {
            backToTopBtn.style.opacity = '1';
            backToTopBtn.style.visibility = 'visible';
        } else {
            backToTopBtn.style.opacity = '0';
            backToTopBtn.style.visibility = 'hidden';
        }
    });
    
    document.body.appendChild(backToTopBtn);
}

// 初始化返回顶部按钮
document.addEventListener('DOMContentLoaded', initBackToTop);