// 🔥 LOG ĐẦU ĐỂ KIỂM TRA LOAD
console.log('🔍 SWAGGER AUTO AUTH JS IS LOADING FROM STATIC...');

(function() {
    'use strict';

    document.addEventListener('DOMContentLoaded', function() {
        if (!window.location.href.includes('swagger-ui')) {
            console.log('❌ NOT IN SWAGGER UI CONTEXT!');
            return;
        }

        console.log('🚀 SWAGGER AUTO AUTH JS LOADED SUCCESSFULLY!');

        function getTokenAndAuthorize() {
            fetch('/get-swagger-token', {
                credentials: 'include',
                headers: { 'Cache-Control': 'no-cache' }
            })
            .then(response => {
                if (!response.ok) throw new Error('HTTP Error: ' + response.status);
                return response.text();
            })
            .then(token => {
                console.log('✅ TOKEN FETCHED:', token ? 'VALID' : 'EMPTY');
                if (token) {
                    const authBtn = document.querySelector('.auth-wrapper .authorize');
                    if (authBtn) {
                        authBtn.click();
                        setTimeout(() => {
                            const input = document.querySelector('.auth-container input[type="text"]');
                            if (input) {
                                input.value = token;
                                input.dispatchEvent(new Event('input', { bubbles: true }));
                                const authorizeBtn = document.querySelector('.auth-container .done');
                                if (authorizeBtn) {
                                    authorizeBtn.click();
                                    console.log('✅ AUTO AUTH COMPLETE - TOKEN ADDED!');
                                }
                            }
                        }, 1500);
                    }
                }
            })
            .catch(err => {
                console.error('❌ FETCH ERROR:', err);
            });
        }

        setTimeout(getTokenAndAuthorize, 2000);
        setInterval(getTokenAndAuthorize, 5000);
    });
})();