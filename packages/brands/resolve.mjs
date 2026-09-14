// SPDX-License-Identifier: AGPL-3.0-only
import catalog from './catalog.json' with {type: 'json'};

const normalized = value => String(value || '').normalize('NFKD').replace(/\p{M}/gu, '')
  .toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
const brands = catalog.brands.map(brand => ({...brand, aliases: brand.aliases.map(normalized)}));

export function stationBrand(station) {
  const match = value => {
    const text = ` ${normalized(value)} `;
    return brands.find(brand => brand.aliases.some(alias => text.includes(` ${alias} `)));
  };
  const brand = match(station.brand) || match(station.name);
  return brand ? {
    brandKey: brand.key, brandLogoUrl: brand.logoUrl, brandLogoSourceUrl: brand.sourceUrl,
  } : {brandKey: null, brandLogoUrl: null, brandLogoSourceUrl: null};
}

export const logoImageHosts = catalog.imageHosts;
